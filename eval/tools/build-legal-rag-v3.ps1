param(
    [string]$QdrantRestUrl = 'http://192.168.52.102:6333',
    [string]$CollectionName = 'laws'
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$datasetDir = Join-Path $repoRoot 'eval\datasets'
$corpusDir = Join-Path $repoRoot 'eval\corpus'
$outputPath = Join-Path $datasetDir 'legal-rag-v3.jsonl'
$manifestPath = Join-Path $corpusDir 'legal-rag-v3-manifest.json'

function Get-ArticleSortKey([string]$articleNoArabic) {
    if ($articleNoArabic -match '^(\d+)(?:-(\d+))?$') {
        $suffix = if ($Matches[2]) { [int64]$Matches[2] } else { 0 }
        return [int64]$Matches[1] * 1000 + $suffix
    }
    return [int64]::MaxValue
}

function Get-Domain([string[]]$sourceNames) {
    $domains = foreach ($name in $sourceNames) {
        switch -Regex ($name) {
            '劳动|社会保险|就业促进|工会|未成年人保护' { 'labor_social'; break }
            '刑法|反有组织犯罪|反电信网络诈骗' { 'criminal'; break }
            '诉讼法|仲裁法|人民调解法' { 'procedure'; break }
            '网络安全|个人信息保护|电子商务' { 'digital'; break }
            '公司法|企业破产|保险法|商标法|专利法|著作权法|消费者权益|产品质量|反垄断|所得税' { 'business_economic'; break }
            '民法典|家庭暴力' { 'civil'; break }
            default { 'other' }
        }
    }
    $unique = @($domains | Sort-Object -Unique)
    return $(if ($unique.Count -eq 1) { $unique[0] } else { 'cross_domain' })
}

function Get-QueryType([string]$category) {
    switch ($category) {
        'direct' { 'semantic_direct' }
        'colloquial' { 'colloquial' }
        'near_miss' { 'semantic_near_miss' }
        'multi_article' { 'multi_article' }
        'cross_law' { 'cross_law' }
        'negation' { 'negation_exception' }
        'long_context' { 'long_context' }
        'out_of_scope_unanswerable' { 'unanswerable' }
        default { $category }
    }
}

function New-HardNegative($point, [string]$reason) {
    [ordered]@{
        business_id = $point.BusinessId
        source_name = $point.SourceName
        article_no = $point.ArticleNo
        article_no_arabic = $point.ArticleNoArabic
        reason = $reason
    }
}

function Get-HardNegatives($goldDocuments, $pointById, $pointsBySource, $pointsByArticle) {
    $goldIds = [System.Collections.Generic.HashSet[string]]::new()
    foreach ($gold in @($goldDocuments)) { [void]$goldIds.Add([string]$gold.business_id) }
    $selectedIds = [System.Collections.Generic.HashSet[string]]::new()
    $result = [System.Collections.Generic.List[object]]::new()
    $coreGold = @($goldDocuments | Sort-Object relevance -Descending | Select-Object -First 1)
    if ($coreGold.Count -eq 0 -or -not $pointById.ContainsKey([string]$coreGold[0].business_id)) {
        return @()
    }
    $goldPoint = $pointById[[string]$coreGold[0].business_id]
    $sameSource = @($pointsBySource[$goldPoint.SourceName] | Sort-Object SortKey, BusinessId)
    $index = -1
    for ($i = 0; $i -lt $sameSource.Count; $i++) {
        if ($sameSource[$i].BusinessId -eq $goldPoint.BusinessId) { $index = $i; break }
    }
    foreach ($offset in @(-1, 1, -2, 2)) {
        $candidateIndex = $index + $offset
        if ($index -lt 0 -or $candidateIndex -lt 0 -or $candidateIndex -ge $sameSource.Count) { continue }
        $candidate = $sameSource[$candidateIndex]
        if (-not $goldIds.Contains($candidate.BusinessId) -and $selectedIds.Add($candidate.BusinessId)) {
            $result.Add((New-HardNegative $candidate '同一法律中的相邻条文，章节和词汇高度接近'))
        }
        if ($result.Count -ge 2) { break }
    }
    foreach ($candidate in @($pointsByArticle[$goldPoint.ArticleNoArabic])) {
        if ($candidate.SourceName -ne $goldPoint.SourceName -and
                -not $goldIds.Contains($candidate.BusinessId) -and
                $selectedIds.Add($candidate.BusinessId)) {
            $result.Add((New-HardNegative $candidate '不同法律中的相同条号，容易被编号或通用法律措辞误召回'))
            break
        }
    }
    return @($result | Select-Object -First 3)
}

function Get-Clause([string]$text, [string]$pattern) {
    $normalized = ($text -replace '\s+', ' ').Trim()
    $clauses = @($normalized -split '[。；！？]' | ForEach-Object { $_.Trim(' ，、：') } |
            Where-Object { $_.Length -ge 10 })
    $selected = if ($pattern) {
        $clauses | Where-Object { $_ -match $pattern } | Select-Object -First 1
    } else {
        $clauses | Where-Object { $_ -notmatch '^为了' } | Select-Object -First 1
    }
    if (-not $selected) { $selected = $clauses | Select-Object -First 1 }
    if (-not $selected) { $selected = $normalized }
    if ($selected.Length -gt 56) { $selected = $selected.Substring(0, 56) + '…' }
    return $selected
}

function Select-Balanced($candidates, [int]$count, $usedIds, [int]$rotation) {
    $groups = @($candidates | Group-Object SourceName | Sort-Object Name)
    $result = [System.Collections.Generic.List[object]]::new()
    $round = 0
    while ($result.Count -lt $count) {
        $added = 0
        for ($step = 0; $step -lt $groups.Count -and $result.Count -lt $count; $step++) {
            $group = $groups[($step + $rotation) % $groups.Count]
            $available = @($group.Group | Where-Object { -not $usedIds.Contains($_.BusinessId) } |
                    Sort-Object SortKey, BusinessId)
            if ($available.Count -eq 0) { continue }
            $fraction = (($round % 3) + 1) / 4.0
            $index = [Math]::Min($available.Count - 1,
                    [Math]::Floor(($available.Count - 1) * $fraction))
            $point = $available[$index]
            if ($usedIds.Add($point.BusinessId)) {
                $result.Add($point)
                $added++
            }
        }
        if ($added -eq 0) { break }
        $round++
    }
    if ($result.Count -ne $count) {
        throw "无法为能力切片选出 $count 个不重复黄金文档，实际为 $($result.Count)"
    }
    return @($result)
}

$scrollBody = @{ limit = 10000; with_payload = $true; with_vector = $false } | ConvertTo-Json
$response = Invoke-RestMethod -Method Post `
    -Uri "$QdrantRestUrl/collections/$CollectionName/points/scroll" `
    -ContentType 'application/json' -Body $scrollBody -TimeoutSec 60

$points = @($response.result.points | ForEach-Object {
    $payload = $_.payload
    [pscustomobject]@{
        BusinessId = [string]$payload.business_id
        SourceName = [string]$payload.source_name
        SourceType = [string]$payload.source_type
        ArticleNo = [string]$payload.article_no
        ArticleNoArabic = [string]$payload.article_no_arabic
        Content = [string]$payload.doc_content
        SortKey = Get-ArticleSortKey ([string]$payload.article_no_arabic)
    }
} | Where-Object { $_.BusinessId -and $_.SourceName -and $_.ArticleNoArabic -and $_.Content })

$pointById = @{}
foreach ($point in $points) { $pointById[$point.BusinessId] = $point }
$pointsBySource = $points | Group-Object SourceName -AsHashTable -AsString
$pointsByArticle = $points | Group-Object ArticleNoArabic -AsHashTable -AsString

$basePaths = @(
    (Join-Path $datasetDir 'civil-code-v2.jsonl'),
    (Join-Path $datasetDir 'labor-criminal-v1.jsonl')
)
$baseCases = foreach ($path in $basePaths) {
    Get-Content -LiteralPath $path -Encoding utf8 | Where-Object { $_ } | ForEach-Object { $_ | ConvertFrom-Json }
}

$allCases = [System.Collections.Generic.List[object]]::new()
foreach ($case in $baseCases) {
    $goldDocuments = @($case.gold_documents)
    $sourceNames = @($goldDocuments | ForEach-Object { [string]$_.source_name } | Where-Object { $_ })
    $queryType = Get-QueryType ([string]$case.category)
    $tags = [System.Collections.Generic.List[string]]::new()
    foreach ($tag in @('reviewed_legacy', [string]$case.category, $queryType, [string]$case.difficulty)) {
        if ($tag -and -not $tags.Contains($tag)) { $tags.Add($tag) }
    }
    if ($goldDocuments.Count -gt 1) { $tags.Add('multiple_gold') }
    if (@($sourceNames | Sort-Object -Unique).Count -gt 1) { $tags.Add('cross_source') }
    $hardNegatives = if ($case.answerable) {
        Get-HardNegatives $goldDocuments $pointById $pointsBySource $pointsByArticle
    } else { @() }
    $allCases.Add([ordered]@{
        id = [string]$case.id
        split = [string]$case.split
        category = [string]$case.category
        difficulty = [string]$case.difficulty
        domain = if ($sourceNames.Count) { Get-Domain $sourceNames } else { 'boundary' }
        query_type = $queryType
        challenge_tags = @($tags)
        question = [string]$case.question
        answerable = [bool]$case.answerable
        gold_documents = $goldDocuments
        hard_negative_documents = $hardNegatives
        gold_answer_points = @($case.gold_answer_points)
        forbidden_claims = @($case.forbidden_claims)
        expected_behavior = $case.expected_behavior
    })
}

$usedIds = [System.Collections.Generic.HashSet[string]]::new()
$typeSpecs = @(
    @{ Name = 'exact_article'; Pattern = $null; Candidates = $points; Rotation = 0 },
    @{ Name = 'exact_term'; Pattern = $null; Candidates = @($points | Where-Object { $_.Content.Length -ge 45 }); Rotation = 9 },
    @{ Name = 'numeric_condition'; Pattern = '[零一二三四五六七八九十百千万两\d]+(日|月|年|岁|小时|倍|%)'; Candidates = @($points | Where-Object { $_.Content -match '[零一二三四五六七八九十百千万两\d]+(日|月|年|岁|小时|倍|%)' }); Rotation = 18 },
    @{ Name = 'negation_exception'; Pattern = '不得|不予|禁止|除外|但是|不适用|可以不'; Candidates = @($points | Where-Object { $_.Content -match '不得|不予|禁止|除外|但是|不适用|可以不' }); Rotation = 27 }
)

$newIndex = 0
foreach ($spec in $typeSpecs) {
    $selected = Select-Balanced $spec.Candidates 25 $usedIds $spec.Rotation
    foreach ($point in $selected) {
        $newIndex++
        $clause = Get-Clause $point.Content $spec.Pattern
        $question = switch ($spec.Name) {
            'exact_article' { '请定位《{0}》{1}：该条规定的核心内容是什么？' -f $point.SourceName, $point.ArticleNo }
            'exact_term' { '在《{0}》中，哪一条规定涉及“{1}”？请检索对应法条。' -f $point.SourceName, $clause }
            'numeric_condition' { '《{0}》中关于“{1}”的具体数字、期限或者年龄规则是什么？' -f $point.SourceName, $clause }
            'negation_exception' { '《{0}》对“{1}”设置了什么禁止、限制或者例外？' -f $point.SourceName, $clause }
        }
        $gold = @([ordered]@{
            business_id = $point.BusinessId
            source_name = $point.SourceName
            article_no = $point.ArticleNo
            article_no_arabic = $point.ArticleNoArabic
            relevance = 2
        })
        $hardNegatives = Get-HardNegatives $gold $pointById $pointsBySource $pointsByArticle
        $specificTag = switch ($spec.Name) {
            'exact_article' { 'article_number_sensitive' }
            'exact_term' { 'lexical_term_sensitive' }
            'numeric_condition' { 'number_sensitive' }
            'negation_exception' { 'negation_sensitive' }
        }
        $allCases.Add([ordered]@{
            id = 'bm25-{0:d3}' -f $newIndex
            split = if ($newIndex -le 68) { 'dev' } else { 'test' }
            category = 'bm25_challenge'
            difficulty = if ($spec.Name -eq 'exact_article') { 'medium' } else { 'hard' }
            domain = Get-Domain @($point.SourceName)
            query_type = $spec.Name
            challenge_tags = @('bm25_advantage', $specificTag, 'exact_law_name', 'hard_negative')
            question = $question
            answerable = $true
            gold_documents = $gold
            hard_negative_documents = $hardNegatives
            gold_answer_points = @($clause)
            forbidden_claims = @('不得把同法相邻条文或其他法律中的相似规定当作该条核心依据')
            expected_behavior = $null
        })
    }
}

$ids = [System.Collections.Generic.HashSet[string]]::new()
$missingGold = [System.Collections.Generic.List[string]]::new()
$missingNegatives = [System.Collections.Generic.List[string]]::new()
foreach ($case in $allCases) {
    if (-not $ids.Add([string]$case.id)) { throw "数据集存在重复ID: $($case.id)" }
    $goldIds = [System.Collections.Generic.HashSet[string]]::new()
    foreach ($gold in @($case.gold_documents)) {
        [void]$goldIds.Add([string]$gold.business_id)
        if (-not $pointById.ContainsKey([string]$gold.business_id)) { $missingGold.Add("$($case.id):$($gold.business_id)") }
    }
    foreach ($negative in @($case.hard_negative_documents)) {
        if (-not $pointById.ContainsKey([string]$negative.business_id)) { $missingNegatives.Add("$($case.id):$($negative.business_id)") }
        if ($goldIds.Contains([string]$negative.business_id)) { throw "困难负例与黄金依据重叠: $($case.id)" }
    }
    if ($case.answerable -and @($case.hard_negative_documents).Count -eq 0) {
        throw "可回答题缺少困难负例: $($case.id)"
    }
}
if ($missingGold.Count) { throw "Qdrant缺少黄金文档: $($missingGold -join ', ')" }
if ($missingNegatives.Count) { throw "Qdrant缺少困难负例: $($missingNegatives -join ', ')" }

$devCount = @($allCases | Where-Object split -eq 'dev').Count
$testCount = @($allCases | Where-Object split -eq 'test').Count
if ($allCases.Count -ne 300 -or $devCount -ne 200 -or $testCount -ne 100) {
    throw "数据规模不符合预期: total=$($allCases.Count), dev=$devCount, test=$testCount"
}

$lines = @($allCases | ForEach-Object { $_ | ConvertTo-Json -Depth 12 -Compress })
[System.IO.File]::WriteAllLines($outputPath, $lines, [System.Text.UTF8Encoding]::new($false))

$manifest = [ordered]@{
    dataset_id = 'legal-rag-v3'
    generated_at = (Get-Date).ToString('yyyy-MM-ddTHH:mm:sszzz')
    qdrant_collection = $CollectionName
    qdrant_point_count = $points.Count
    source_count = @($points.SourceName | Sort-Object -Unique).Count
    base_datasets = @('civil-code-v2', 'labor-criminal-v1')
    total_cases = $allCases.Count
    dev_cases = $devCount
    test_cases = $testCount
    answerable_cases = @($allCases | Where-Object answerable).Count
    unanswerable_cases = @($allCases | Where-Object { -not $_.answerable }).Count
    bm25_challenge_distribution = [ordered]@{
        exact_article = 25
        exact_term = 25
        numeric_condition = 25
        negation_exception = 25
    }
    hard_negative_strategy = @(
        'same_source_adjacent_article',
        'same_article_number_cross_source'
    )
}
[System.IO.File]::WriteAllText($manifestPath,
        ($manifest | ConvertTo-Json -Depth 8), [System.Text.UTF8Encoding]::new($false))

Write-Output "Generated $outputPath"
Write-Output "total=$($allCases.Count), dev=$devCount, test=$testCount, qdrantPoints=$($points.Count)"
