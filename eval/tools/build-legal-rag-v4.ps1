param(
    [string]$QdrantRestUrl = 'http://192.168.52.102:6333',
    [string]$CollectionName = 'laws'
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$outputPath = Join-Path $repoRoot 'eval\datasets\legal-rag-v4.jsonl'
$manifestPath = Join-Path $repoRoot 'eval\corpus\legal-rag-v4-manifest.json'

function Domain-Of([string[]]$names) {
    $values = foreach ($name in $names) {
        switch -Regex ($name) {
            '劳动|社会保险|就业促进|工会|未成年人保护' { 'labor_social'; break }
            '刑法|反有组织犯罪|反电信网络诈骗' { 'criminal'; break }
            '诉讼法|仲裁法|人民调解法' { 'procedure'; break }
            '网络安全|个人信息保护|电子商务' { 'digital'; break }
            '公司法|企业破产|保险法|商标法|产品质量|消费者权益' { 'business_economic'; break }
            '家庭暴力|民法典' { 'civil'; break }
            default { 'other' }
        }
    }
    $unique = @($values | Sort-Object -Unique)
    if ($unique.Count -eq 1) { return $unique[0] }
    return 'cross_domain'
}

function Negative-Of($point, [string]$reason) {
    [ordered]@{
        business_id = $point.business_id
        source_name = $point.source_name
        article_no = $point.article_no
        article_no_arabic = $point.article_no_arabic
        reason = $reason
    }
}

function Hard-Negatives($goldIds, $pointById, $bySource, $byArticle) {
    $goldSet = [System.Collections.Generic.HashSet[string]]::new()
    foreach ($id in $goldIds) { [void]$goldSet.Add($id) }
    $chosen = [System.Collections.Generic.HashSet[string]]::new()
    $result = [System.Collections.Generic.List[object]]::new()
    $core = $pointById[$goldIds[0]]
    $same = @($bySource[$core.source_name] | Sort-Object sort_key, business_id)
    $index = -1
    for ($i = 0; $i -lt $same.Count; $i++) {
        if ($same[$i].business_id -eq $core.business_id) { $index = $i; break }
    }
    foreach ($offset in @(-1, 1, -2, 2)) {
        $candidateIndex = $index + $offset
        if ($index -lt 0 -or $candidateIndex -lt 0 -or $candidateIndex -ge $same.Count) { continue }
        $candidate = $same[$candidateIndex]
        if (-not $goldSet.Contains($candidate.business_id) -and $chosen.Add($candidate.business_id)) {
            $result.Add((Negative-Of $candidate '同法相邻条文，主题和法律措辞接近但适用条件不同'))
        }
        if ($result.Count -ge 2) { break }
    }
    foreach ($candidate in @($byArticle[$core.article_no_arabic])) {
        if ($candidate.source_name -ne $core.source_name -and
                -not $goldSet.Contains($candidate.business_id) -and $chosen.Add($candidate.business_id)) {
            $result.Add((Negative-Of $candidate '不同法律中的相同条号，属于精确编号检索的干扰项'))
            break
        }
    }
    return @($result | Select-Object -First 3)
}

function Answer-Summary([string]$content) {
    $normalized = ($content -replace '\s+', ' ').Trim()
    if ($normalized.Length -gt 220) { return $normalized.Substring(0, 220) + '…' }
    return $normalized
}

$specs = @(
    # 数字、期限、年龄和倍数：20题
    @{id='num-001'; type='numeric_deadline'; q='社保已经缴了十四年多，马上到退休年龄了。想按月领基本养老金，法律要求累计至少缴满多少年？'; gold=@('law_中华人民共和国社会保险法_16_0')},
    @{id='num-002'; type='numeric_deadline'; q='我失业前累计缴了六年失业保险，最长可以领多久失业保险金？如果缴了十一年又是多少？'; gold=@('law_中华人民共和国社会保险法_46_0')},
    @{id='num-003'; type='numeric_deadline'; q='被公司违法辞退后一直协商，准备申请劳动仲裁。劳动争议的一般仲裁时效到底是一年还是三年？'; gold=@('law_中华人民共和国劳动争议调解仲裁法_27_0')},
    @{id='num-004'; type='numeric_deadline'; q='劳动仲裁委员会收到申请后，最迟几天内应当决定是否受理并通知申请人？'; gold=@('law_中华人民共和国劳动争议调解仲裁法_29_0')},
    @{id='num-005'; type='numeric_deadline'; q='单位基层工会委员会一届究竟可以任三年还是五年？地方总工会的任期又是多少年？'; gold=@('law_中华人民共和国工会法_16_0')},
    @{id='num-006'; type='numeric_deadline'; q='遭遇家暴后申请人身安全保护令，普通情况下法院多久要作决定？情况特别紧急时又是多久？'; gold=@('law_中华人民共和国反家庭暴力法_28_0')},
    @{id='num-007'; type='numeric_deadline'; q='对人身安全保护令或者驳回申请的裁定不服，几天内可以申请复议？复议期间保护令会不会暂停？'; gold=@('law_中华人民共和国反家庭暴力法_31_0')},
    @{id='num-008'; type='numeric_deadline'; q='家长把七岁的孩子整晚单独留在家里，又让十五岁的孩子脱离监护独自生活，两个年龄界限分别怎么规定？'; gold=@('law_中华人民共和国未成年人保护法_21_0')},
    @{id='num-009'; type='numeric_deadline'; q='公司法定代表人辞任后，公司最迟应在多少天内确定新的法定代表人？'; gold=@('law_中华人民共和国公司法_10_0')},
    @{id='num-010'; type='numeric_deadline'; q='股东认为董事会决议程序违法，通常应当从决议作出之日起多少日内请求法院撤销？'; gold=@('law_中华人民共和国公司法_26_0')},
    @{id='num-011'; type='numeric_deadline'; q='新设有限责任公司的股东认缴出资，一般要从公司成立之日起几年内缴足？'; gold=@('law_中华人民共和国公司法_47_0')},
    @{id='num-012'; type='numeric_deadline'; q='商标申请被驳回后，申请人有多少天申请复审？评审通常应在几个月内作出决定？'; gold=@('law_中华人民共和国商标法_34_0')},
    @{id='num-013'; type='numeric_deadline'; q='注册商标的有效期到底是五年、十年还是二十年，从哪一天开始计算？'; gold=@('law_中华人民共和国商标法_39_0')},
    @{id='num-014'; type='numeric_deadline'; q='黑社会性质组织的组织者服刑完毕后，公安机关决定其报告财产和日常活动的期限最长能有几年？'; gold=@('law_中华人民共和国反有组织犯罪法_19_0')},
    @{id='num-015'; type='numeric_deadline'; q='因电信网络诈骗受过刑事处罚的人，处罚完毕后可能被限制出境多久？'; gold=@('law_中华人民共和国反电信网络诈骗法_36_0')},
    @{id='num-016'; type='numeric_deadline'; q='行政复议决定已经送达，当事人仍不服，通常要在多少日内提起行政诉讼？'; gold=@('law_中华人民共和国行政诉讼法_45_0')},
    @{id='num-017'; type='numeric_deadline'; q='没有先申请行政复议而直接起诉，一般起诉期限是六个月吗？不动产案件最长保护期限又是多少年？'; gold=@('law_中华人民共和国行政诉讼法_46_0')},
    @{id='num-018'; type='numeric_deadline'; q='人民调解协议生效后，双方想申请司法确认，最迟应当在多少天内共同提出？'; gold=@('law_中华人民共和国人民调解法_33_0')},
    @{id='num-019'; type='numeric_deadline'; q='债权人申请公司破产后，法院通知债务人、债务人提出异议以及法院裁定受理分别有哪些期限？'; gold=@('law_中华人民共和国企业破产法_10_0')},
    @{id='num-020'; type='numeric_deadline'; q='保险公司收到理赔请求后，复杂案件通常多少天内核定？达成赔付协议后又应在多少天内付款？'; gold=@('law_中华人民共和国保险法_23_0')},

    # 相似概念和相邻制度辨析：20题
    @{id='concept-001'; type='concept_disambiguation'; q='养老保险个人账户里的钱能不能提前取出来？这和达到退休年龄后按月领取养老金是同一件事吗？'; gold=@('law_中华人民共和国社会保险法_14_0','law_中华人民共和国社会保险法_16_0')},
    @{id='concept-002'; type='concept_disambiguation'; q='劳动争议调解协议已经签了，它是否就等同于具有终局效力的仲裁裁决？调解不成后还能走什么程序？'; gold=@('law_中华人民共和国劳动争议调解仲裁法_14_0','law_中华人民共和国劳动争议调解仲裁法_5_0')},
    @{id='concept-003'; type='concept_disambiguation'; q='招聘时笼统地说“只要男性”和在确实不适合妇女的特殊岗位限制招聘，法律评价完全一样吗？'; gold=@('law_中华人民共和国就业促进法_26_0','law_中华人民共和国就业促进法_27_0')},
    @{id='concept-004'; type='concept_disambiguation'; q='职工依法参加工会是一项权利，还是必须取得企业老板批准后才能参加？'; gold=@('law_中华人民共和国工会法_3_0')},
    @{id='concept-005'; type='concept_disambiguation'; q='公安机关出具家暴告诫书和法院签发人身安全保护令是一回事吗？分别由谁作出？'; gold=@('law_中华人民共和国反家庭暴力法_16_0','law_中华人民共和国反家庭暴力法_23_0')},
    @{id='concept-006'; type='concept_disambiguation'; q='家长偶尔外出委托别人照看孩子，和直接让未满十六周岁的孩子脱离监护独立生活，在法律上如何区分？'; gold=@('law_中华人民共和国未成年人保护法_21_0','law_中华人民共和国未成年人保护法_22_0')},
    @{id='concept-007'; type='concept_disambiguation'; q='公司章程限制了法定代表人的权限，他超越内部权限与善意客户签约，是否当然对公司不生效？'; gold=@('law_中华人民共和国公司法_11_0')},
    @{id='concept-008'; type='concept_disambiguation'; q='公司会议程序只有轻微瑕疵和程序违法且实质影响决议，股东都能请求撤销吗？'; gold=@('law_中华人民共和国公司法_26_0')},
    @{id='concept-009'; type='concept_disambiguation'; q='一个标志不能作为商标使用，与一个标志缺乏显著性不能注册，是不是完全相同的审查理由？'; gold=@('law_中华人民共和国商标法_10_0','law_中华人民共和国商标法_11_0')},
    @{id='concept-010'; type='concept_disambiguation'; q='商标申请被直接驳回后的复审，与初步审定公告后第三人提出异议，程序上有什么区别？'; gold=@('law_中华人民共和国商标法_34_0','law_中华人民共和国商标法_35_0')},
    @{id='concept-011'; type='concept_disambiguation'; q='法律所说的“有组织犯罪”和普通多人共同违法是否是一回事？认定时需要哪些组织性特征？'; gold=@('law_中华人民共和国反有组织犯罪法_2_0')},
    @{id='concept-012'; type='concept_disambiguation'; q='运营商因为用户异常办卡而加强核验，与个人非法买卖批量插卡设备，属于同一种违法行为吗？'; gold=@('law_中华人民共和国反电信网络诈骗法_10_0','law_中华人民共和国反电信网络诈骗法_14_0')},
    @{id='concept-013'; type='concept_disambiguation'; q='违法行为已经构成犯罪时，行政机关还能不能只罚款了事？行政责任、民事责任和刑事责任是什么关系？'; gold=@('law_中华人民共和国行政处罚法_8_0')},
    @{id='concept-014'; type='concept_disambiguation'; q='行政复议后起诉和未经复议直接起诉，起算点与期限是否完全相同？'; gold=@('law_中华人民共和国行政诉讼法_45_0','law_中华人民共和国行政诉讼法_46_0')},
    @{id='concept-015'; type='concept_disambiguation'; q='双方没有仲裁协议时一方单独申请仲裁，与已有有效仲裁协议后另一方又去法院起诉，处理结果有什么不同？'; gold=@('law_中华人民共和国仲裁法_4_0','law_中华人民共和国仲裁法_5_0')},
    @{id='concept-016'; type='concept_disambiguation'; q='人民调解协议签字生效后，与经过法院司法确认后的协议，在申请强制执行方面有什么差别？'; gold=@('law_中华人民共和国人民调解法_31_0','law_中华人民共和国人民调解法_33_0')},
    @{id='concept-017'; type='concept_disambiguation'; q='破产受理前双方都没履行完的合同，是自动解除，还是由管理人选择继续履行或解除？'; gold=@('law_中华人民共和国企业破产法_18_0')},
    @{id='concept-018'; type='concept_disambiguation'; q='保险合同中的投保人、被保险人和受益人是同一个身份吗？三者分别指什么？'; gold=@('law_中华人民共和国保险法_12_0')},
    @{id='concept-019'; type='concept_disambiguation'; q='网上自己开店的人和提供交易场所、撮合双方的平台，在电子商务法中是否都叫“平台经营者”？'; gold=@('law_中华人民共和国电子商务法_9_0')},
    @{id='concept-020'; type='concept_disambiguation'; q='产品“不符合质量要求”和法律上的“缺陷产品”能直接画等号吗？缺陷概念强调什么风险？'; gold=@('law_中华人民共和国产品质量法_46_0')},

    # 否定、禁止、除外和反向条件：20题
    @{id='neg-001'; type='negation_exception'; q='养老保险个人账户是不是无论什么情况都可以提前支取？参保人死亡后账户余额也不能继承吗？'; gold=@('law_中华人民共和国社会保险法_14_0')},
    @{id='neg-002'; type='negation_exception'; q='公司招聘可以因为应聘者是女性就提高标准，并在合同里约定几年内不得结婚生育吗？'; gold=@('law_中华人民共和国就业促进法_27_0')},
    @{id='neg-003'; type='negation_exception'; q='用人单位能否仅因为求职者是传染病病原携带者就拒绝录用？法律规定的例外是什么？'; gold=@('law_中华人民共和国就业促进法_30_0')},
    @{id='neg-004'; type='negation_exception'; q='企业主要负责人的近亲属能不能直接成为本企业基层工会委员会成员？'; gold=@('law_中华人民共和国工会法_10_0')},
    @{id='neg-005'; type='negation_exception'; q='所谓“家务事”是不是可以排除反家庭暴力法，监护人教育孩子时也可以随意动手？'; gold=@('law_中华人民共和国反家庭暴力法_3_0','law_中华人民共和国反家庭暴力法_12_0')},
    @{id='neg-006'; type='negation_exception'; q='监护人能否放任未成年人吸烟、饮酒、流浪乞讨，或者强迫其参加不适龄劳动？'; gold=@('law_中华人民共和国未成年人保护法_17_0')},
    @{id='neg-007'; type='negation_exception'; q='公司为控股股东提供担保时，该股东能不能参加股东会表决？'; gold=@('law_中华人民共和国公司法_15_0')},
    @{id='neg-008'; type='negation_exception'; q='股东会召集程序有任何微小瑕疵都必须撤销决议吗？哪种轻微瑕疵属于例外？'; gold=@('law_中华人民共和国公司法_26_0')},
    @{id='neg-009'; type='negation_exception'; q='外国国旗、国际组织标志一律绝对不能作为商标吗？条文中有没有同意或授权的例外？'; gold=@('law_中华人民共和国商标法_10_0')},
    @{id='neg-010'; type='negation_exception'; q='个人可以买一套能批量插电话卡、自动切换网络地址的设备自己研究使用吗？哪些设备软件被明确禁止制造、买卖、提供或使用？'; gold=@('law_中华人民共和国反电信网络诈骗法_14_0')},
    @{id='neg-011'; type='negation_exception'; q='行政处罚所依据的规定没有向社会公布，行政机关还能把它作为处罚依据吗？'; gold=@('law_中华人民共和国行政处罚法_5_0')},
    @{id='neg-012'; type='negation_exception'; q='受移送的行政案件法院觉得自己也没管辖权，能不能再转给第三家法院？'; gold=@('law_中华人民共和国行政诉讼法_22_0')},
    @{id='neg-013'; type='negation_exception'; q='没有任何仲裁协议，一方能否强迫另一方去仲裁？'; gold=@('law_中华人民共和国仲裁法_4_0')},
    @{id='neg-014'; type='negation_exception'; q='人民调解委员会想主动调解，但一方明确说不接受，还能不能继续强行调解？'; gold=@('law_中华人民共和国人民调解法_17_0')},
    @{id='neg-015'; type='negation_exception'; q='曾因故意犯罪受过刑事处罚的人，是否还能被法院指定为破产管理人？'; gold=@('law_中华人民共和国企业破产法_24_0')},
    @{id='neg-016'; type='negation_exception'; q='普通公司或个人只要取得营业执照，就可以经营商业保险业务吗？'; gold=@('law_中华人民共和国保险法_6_0')},
    @{id='neg-017'; type='negation_exception'; q='在网上偶尔卖自家种的蔬菜也必须办理市场主体登记吗？电子商务经营者登记有哪些例外？'; gold=@('law_中华人民共和国电子商务法_10_0')},
    @{id='neg-018'; type='negation_exception'; q='住宅楼本身的施工质量是否直接适用产品质量法？楼里使用的建筑材料又是否完全不适用？'; gold=@('law_中华人民共和国产品质量法_2_0')},
    @{id='neg-019'; type='negation_exception'; q='App为了“以后也许有用”就把通讯录、相册、定位全部收集，是否符合个人信息处理的最小必要原则？'; gold=@('law_中华人民共和国个人信息保护法_5_0','law_中华人民共和国个人信息保护法_6_0')},
    @{id='neg-020'; type='negation_exception'; q='智能设备厂商发现产品存在严重安全漏洞后，能否保持沉默并在承诺的维护期内停止安全更新？'; gold=@('law_中华人民共和国网络安全法_24_0')},

    # 生活化、简称、噪声和不规范表达：20题
    @{id='life-001'; type='colloquial_noisy'; q='老板说公司最近手头紧，社保先断几个月，等赚钱了再补，这种理由就能缓缴减免吗？'; gold=@('law_中华人民共和国社会保险法_60_0')},
    @{id='life-002'; type='colloquial_noisy'; q='我自己嫌工作累主动裸辞，也已经登记找工作了，能不能只凭“交过社保”就领失业金？'; gold=@('law_中华人民共和国社会保险法_45_0')},
    @{id='life-003'; type='colloquial_noisy'; q='老板欠薪拖了两年，但这两年我一直还在这家公司上班，工资争议也一定按普通一年仲裁时效算吗？'; gold=@('law_中华人民共和国劳动争议调解仲裁法_27_0')},
    @{id='life-004'; type='colloquial_noisy'; q='招聘HR直说“女生以后要生娃，麻烦，只招男的”，岗位又不是特殊工种，这合法么？'; gold=@('law_中华人民共和国就业促进法_27_0')},
    @{id='life-005'; type='colloquial_noisy'; q='我因为组织同事参加工会活动被开了，公司说赔一个月工资就完事，工会法怎么处理？'; gold=@('law_中华人民共和国工会法_53_0')},
    @{id='life-006'; type='colloquial_noisy'; q='家里大人出去打麻将，把七岁娃一个人锁在家过夜，说孩子很懂事不用管，法律允许吗？'; gold=@('law_中华人民共和国未成年人保护法_21_0')},
    @{id='life-007'; type='colloquial_noisy'; q='公司内部规定经理最多只能签五十万的合同，他却和不知情客户签了一百万，公司能拿内部规定直接赖账吗？'; gold=@('law_中华人民共和国公司法_11_0')},
    @{id='life-008'; type='colloquial_noisy'; q='公司想给大股东的私人债务做担保，大股东自己还能在股东会上给自己投赞成票吗？'; gold=@('law_中华人民共和国公司法_15_0')},
    @{id='life-009'; type='colloquial_noisy'; q='商标申请被打回来，通知已经收到十来天了，我还想申请复审，法定窗口到底有多长？'; gold=@('law_中华人民共和国商标法_34_0')},
    @{id='life-010'; type='colloquial_noisy'; q='警方查涉黑财产时把嫌疑人家属完全无关的银行卡也冻了，查清无关后还一直不解冻，合法么？'; gold=@('law_中华人民共和国反有组织犯罪法_41_0')},
    @{id='life-011'; type='colloquial_noisy'; q='我名下电话卡太多，再去营业厅办卡时运营商说情况异常要多核验，甚至拒绝办卡，它有这个权利吗？'; gold=@('law_中华人民共和国反电信网络诈骗法_10_0')},
    @{id='life-012'; type='colloquial_noisy'; q='网购合同写着“出了纠纷只能去买家所在地法院”，这种协议选法院的约定有没有法律边界？'; gold=@('law_中华人民共和国民事诉讼法_35_0')},
    @{id='life-013'; type='colloquial_noisy'; q='有人违法已经涉嫌犯罪，执法部门却说“交罚款就不移送了”，这样能用行政处罚顶掉刑事责任吗？'; gold=@('law_中华人民共和国行政处罚法_8_0')},
    @{id='life-014'; type='colloquial_noisy'; q='行政复议结果我不服，拖了二十天才想起起诉，一般期限到底是多久？'; gold=@('law_中华人民共和国行政诉讼法_45_0')},
    @{id='life-015'; type='colloquial_noisy'; q='合同明明有有效仲裁条款，对方还是直接把我告到法院，法院通常会受理吗？'; gold=@('law_中华人民共和国仲裁法_5_0')},
    @{id='life-016'; type='colloquial_noisy'; q='邻里纠纷里我明确不想调解，调解员说“来了就必须调”，人民调解能强迫进行吗？'; gold=@('law_中华人民共和国人民调解法_17_0')},
    @{id='life-017'; type='colloquial_noisy'; q='法院受理破产后，管理人两个月都不说原来没履行完的合同怎么办，这份合同会一直悬着吗？'; gold=@('law_中华人民共和国企业破产法_18_0')},
    @{id='life-018'; type='colloquial_noisy'; q='保险公司收到材料后一直说案子复杂，两个月也不给核定结果，保险法对核定和赔付有时间要求吗？'; gold=@('law_中华人民共和国保险法_23_0')},
    @{id='life-019'; type='colloquial_noisy'; q='网店平台准备关站，今天弹个通知明天就停服务，可以吗？需要提前多久持续公示？'; gold=@('law_中华人民共和国电子商务法_16_0')},
    @{id='life-020'; type='colloquial_noisy'; q='手电筒App一打开就强制读取联系人、相册和精确位置，不给就不能用，这算不算收集过头？'; gold=@('law_中华人民共和国个人信息保护法_6_0')},

    # 跨法律、多依据和责任层级：15题
    @{id='cross-001'; type='cross_law_multi'; q='父亲长期殴打十岁的孩子。除了未成年人保护规则，能否申请人身安全保护令，需要同时关注哪些法律依据？'; gold=@('law_中华人民共和国未成年人保护法_17_0','law_中华人民共和国反家庭暴力法_12_0','law_中华人民共和国反家庭暴力法_23_0')},
    @{id='cross-002'; type='cross_law_multi'; q='电商平台知道店铺销售危及人身安全的缺陷产品却不采取措施，平台责任和产品侵权分别应检索哪些规定？'; gold=@('law_中华人民共和国电子商务法_38_0','law_中华人民共和国产品质量法_43_0')},
    @{id='cross-003'; type='cross_law_multi'; q='平台发生大规模用户信息泄露后，既没有及时补救也没有通知用户和主管部门，网络与个人信息保护规则如何共同约束？'; gold=@('law_中华人民共和国个人信息保护法_57_0','law_中华人民共和国网络安全法_44_0')},
    @{id='cross-004'; type='cross_law_multi'; q='有人专门出售批量插卡设备并帮助诈骗团伙转账，即使尚未达到刑事定罪标准，专项法律规定了什么责任？'; gold=@('law_中华人民共和国反电信网络诈骗法_14_0','law_中华人民共和国反电信网络诈骗法_38_0')},
    @{id='cross-005'; type='cross_law_multi'; q='招聘广告写“女性不要、残疾人不要”，就业促进法中哪些平等就业规定应当一起检索？'; gold=@('law_中华人民共和国就业促进法_26_0','law_中华人民共和国就业促进法_27_0','law_中华人民共和国就业促进法_29_0')},
    @{id='cross-006'; type='cross_law_multi'; q='职工仅因参加工会活动被解除劳动合同，除了恢复工作和补发报酬，工会法还规定了什么赔偿路径？'; gold=@('law_中华人民共和国工会法_53_0')},
    @{id='cross-007'; type='cross_law_multi'; q='同一违法行为既损害消费者又涉嫌犯罪，行政机关能否只作行政处罚？民事赔偿和刑事追责会不会被排除？'; gold=@('law_中华人民共和国行政处罚法_8_0','law_中华人民共和国产品质量法_49_0')},
    @{id='cross-008'; type='cross_law_multi'; q='缺陷产品交付多年后造成人身损害，主张赔偿时既要判断两年诉讼时效，也要判断十年最长期间，应依据哪条规定？'; gold=@('law_中华人民共和国产品质量法_45_0')},
    @{id='cross-009'; type='cross_law_multi'; q='企业破产清偿时拖欠的职工工资、医疗伤残补助和欠缴社保费用处于什么清偿顺位？'; gold=@('law_中华人民共和国企业破产法_113_0')},
    @{id='cross-010'; type='cross_law_multi'; q='合同中存在仲裁条款，一方仍向法院起诉。判断法院是否受理以及仲裁是否必须基于双方合意，应结合哪些规定？'; gold=@('law_中华人民共和国仲裁法_4_0','law_中华人民共和国仲裁法_5_0')},
    @{id='cross-011'; type='cross_law_multi'; q='人民调解达成协议后，一方担心对方反悔，怎样通过司法确认获得可以申请强制执行的效力？'; gold=@('law_中华人民共和国人民调解法_31_0','law_中华人民共和国人民调解法_33_0')},
    @{id='cross-012'; type='cross_law_multi'; q='网购平台要求商家和用户提交超出交易所需的大量个人信息，平台经营规则与个人信息最小必要原则如何同时适用？'; gold=@('law_中华人民共和国电子商务法_23_0','law_中华人民共和国个人信息保护法_6_0')},
    @{id='cross-013'; type='cross_law_multi'; q='网络服务处理不满十四周岁未成年人的个人信息，应同时关注未成年人网络保护和敏感个人信息规则中的哪些条文？'; gold=@('law_中华人民共和国未成年人保护法_72_0','law_中华人民共和国个人信息保护法_28_0')},
    @{id='cross-014'; type='cross_law_multi'; q='保险公司收集健康、医疗等高度敏感信息用于核保时，除了保险合同询问和如实告知规则，还要注意哪类个人信息保护要求？'; gold=@('law_中华人民共和国保险法_16_0','law_中华人民共和国个人信息保护法_28_0')},
    @{id='cross-015'; type='cross_law_multi'; q='电商平台收到商标权人关于假货链接的合格通知后仍不处理，平台知识产权措施与商标侵权判断分别涉及什么规则？'; gold=@('law_中华人民共和国电子商务法_42_0','law_中华人民共和国商标法_57_0')},

    # 只保留少量精确词汇诊断题：5题
    @{id='exact-001'; type='exact_term_diagnostic'; q='法律术语“敏感个人信息”指什么，为什么生物识别、医疗健康、金融账户和不满十四周岁未成年人信息会被单独保护？'; gold=@('law_中华人民共和国个人信息保护法_28_0')},
    @{id='exact-002'; type='exact_term_diagnostic'; q='仲裁中的“一裁终局”具体意味着什么？裁决被撤销或者不予执行后是否还有例外路径？'; gold=@('law_中华人民共和国仲裁法_10_0')},
    @{id='exact-003'; type='exact_term_diagnostic'; q='“人身安全保护令”由哪个机关作出，申请它需要以已经提起离婚诉讼为前提吗？'; gold=@('law_中华人民共和国反家庭暴力法_23_0')},
    @{id='exact-004'; type='exact_term_diagnostic'; q='破产程序中的“管理人”由谁指定，可以由哪些机构或者人员担任？'; gold=@('law_中华人民共和国企业破产法_22_0','law_中华人民共和国企业破产法_24_0')},
    @{id='exact-005'; type='exact_term_diagnostic'; q='电子商务法中的“电子商务平台经营者”和“平台内经营者”分别是什么？'; gold=@('law_中华人民共和国电子商务法_9_0')}
)

$body = @{limit=10000; with_payload=$true; with_vector=$false} | ConvertTo-Json
$rawPoints = (Invoke-RestMethod -Method Post `
    -Uri "$QdrantRestUrl/collections/$CollectionName/points/scroll" `
    -ContentType 'application/json' -Body $body -TimeoutSec 60).result.points
$points = @($rawPoints | ForEach-Object {
    $p = $_.payload
    $article = [string]$p.article_no_arabic
    $primary = if ($article -match '^(\d+)') { [int64]$Matches[1] } else { [int64]::MaxValue }
    [pscustomobject]@{
        business_id=[string]$p.business_id; source_name=[string]$p.source_name
        article_no=[string]$p.article_no; article_no_arabic=$article
        doc_content=[string]$p.doc_content; sort_key=$primary
    }
} | Where-Object {$_.business_id -and $_.source_name -and $_.doc_content})
$pointById = @{}; foreach ($point in $points) { $pointById[$point.business_id] = $point }
$bySource = $points | Group-Object source_name -AsHashTable -AsString
$byArticle = $points | Group-Object article_no_arabic -AsHashTable -AsString

$limits = @{numeric_deadline=13; concept_disambiguation=13; negation_exception=13; colloquial_noisy=13; cross_law_multi=10; exact_term_diagnostic=4}
$seenByType = @{}
$cases = [System.Collections.Generic.List[object]]::new()
foreach ($spec in $specs) {
    $seenByType[$spec.type] = 1 + [int]$seenByType[$spec.type]
    $split = if ($seenByType[$spec.type] -le $limits[$spec.type]) {'dev'} else {'test'}
    $goldDocs = [System.Collections.Generic.List[object]]::new()
    $answerPoints = [System.Collections.Generic.List[string]]::new()
    for ($i = 0; $i -lt $spec.gold.Count; $i++) {
        $id = $spec.gold[$i]
        if (-not $pointById.ContainsKey($id)) { throw "黄金文档不存在: $($spec.id) -> $id" }
        $point = $pointById[$id]
        $goldDocs.Add([ordered]@{
            business_id=$id; source_name=$point.source_name; article_no=$point.article_no
            article_no_arabic=$point.article_no_arabic; relevance=if($i -eq 0){2}else{1}
        })
        $answerPoints.Add((Answer-Summary $point.doc_content))
    }
    $tags = switch ($spec.type) {
        'numeric_deadline' {@('number_sensitive','deadline_sensitive','hard_negative')}
        'concept_disambiguation' {@('near_miss','concept_boundary','hard_negative')}
        'negation_exception' {@('negation_sensitive','exception_sensitive','hard_negative')}
        'colloquial_noisy' {@('colloquial','noisy_expression','hard_negative')}
        'cross_law_multi' {@('cross_law','multiple_gold','multi_rule','hard_negative')}
        'exact_term_diagnostic' {@('bm25_diagnostic','lexical_term_sensitive','hard_negative')}
    }
    $sources = @($goldDocs | ForEach-Object source_name | Sort-Object -Unique)
    $cases.Add([ordered]@{
        id="v4-$($spec.id)"; split=$split; category=$spec.type
        difficulty=if($spec.type -eq 'exact_term_diagnostic'){'medium'}else{'hard'}
        domain=Domain-Of $sources; query_type=$spec.type; challenge_tags=$tags
        question=$spec.q; answerable=$true; gold_documents=@($goldDocs)
        hard_negative_documents=Hard-Negatives $spec.gold $pointById $bySource $byArticle
        gold_answer_points=@($answerPoints)
        forbidden_claims=@('不得用相邻但适用条件不同的条文替代黄金依据','不得忽略问题中的数字、否定、例外或主体条件')
        expected_behavior=$null
    })
}

if ($cases.Count -ne 100) { throw "题目总数错误: $($cases.Count)" }
$dev = @($cases | Where-Object split -eq dev).Count
$test = @($cases | Where-Object split -eq test).Count
if ($dev -ne 66 -or $test -ne 34) { throw "分层划分错误: dev=$dev test=$test" }
$ids = [System.Collections.Generic.HashSet[string]]::new()
foreach ($case in $cases) {
    if (-not $ids.Add($case.id)) { throw "重复题号: $($case.id)" }
    $goldSet = [System.Collections.Generic.HashSet[string]]::new()
    foreach ($gold in $case.gold_documents) { [void]$goldSet.Add($gold.business_id) }
    if (@($case.hard_negative_documents).Count -lt 2) { throw "困难负例不足: $($case.id)" }
    foreach ($negative in $case.hard_negative_documents) {
        if ($goldSet.Contains($negative.business_id)) { throw "黄金与负例重叠: $($case.id)" }
    }
}

$lines = @($cases | ForEach-Object { $_ | ConvertTo-Json -Depth 12 -Compress })
[IO.File]::WriteAllLines($outputPath, $lines, [Text.UTF8Encoding]::new($false))
$distribution = [ordered]@{}; foreach($g in ($cases | Group-Object query_type)){ $distribution[$g.Name]=$g.Count }
$manifest = [ordered]@{
    dataset_id='legal-rag-v4'; generated_at=(Get-Date).ToString('yyyy-MM-ddTHH:mm:sszzz')
    total_cases=100; dev_cases=$dev; test_cases=$test; answerable_cases=100
    reused_questions=0; qdrant_point_count=$points.Count
    query_type_distribution=$distribution
    source_count=@($cases.gold_documents.source_name | Sort-Object -Unique).Count
    hard_negative_strategy=@('same_source_adjacent_article','same_article_number_cross_source')
}
[IO.File]::WriteAllText($manifestPath, ($manifest | ConvertTo-Json -Depth 8), [Text.UTF8Encoding]::new($false))
Write-Output "Generated legal-rag-v4: total=100 dev=$dev test=$test sources=$($manifest.source_count)"
