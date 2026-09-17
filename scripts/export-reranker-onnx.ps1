param(
    [string]$ModelId = "BAAI/bge-reranker-v2-m3",
    [string]$OutputDirectory = "models/reranker",
    [string]$PythonExecutable = "python"
)

$ErrorActionPreference = "Stop"
$workspace = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$venvDirectory = Join-Path $workspace ".venv-reranker"
$outputPath = Join-Path $workspace $OutputDirectory

& $PythonExecutable -m venv $venvDirectory
$venvPython = Join-Path $venvDirectory "Scripts/python.exe"
$optimumCli = Join-Path $venvDirectory "Scripts/optimum-cli.exe"

& $venvPython -m pip install --upgrade pip
& $venvPython -m pip install "optimum[onnxruntime]" transformers torch sentencepiece hf_xet
& $optimumCli export onnx --model $ModelId --task text-classification $outputPath

$modelFile = Join-Path $outputPath "model.onnx"
$tokenizerFile = Join-Path $outputPath "tokenizer.json"
if (-not (Test-Path -LiteralPath $modelFile) -or -not (Test-Path -LiteralPath $tokenizerFile)) {
    throw "ONNX导出结束，但未找到 model.onnx 或 tokenizer.json：$outputPath"
}

Write-Host "Reranker ONNX模型已准备完成：$outputPath"
