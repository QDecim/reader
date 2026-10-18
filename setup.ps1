# Run once in the project root: powershell -ExecutionPolicy Bypass -File setup.ps1
# ASCII-only on purpose: Windows PowerShell 5.1 misreads UTF-8 files without BOM.
$ErrorActionPreference = "Stop"; $ProgressPreference = "SilentlyContinue"
$root = $PSScriptRoot; $ver = "1.13.8"
$base = "https://github.com/k2-fsa/sherpa-onnx/releases/download"
New-Item -ItemType Directory -Force "$root\app\libs" | Out-Null
$aar = "$root\app\libs\sherpa-onnx-$ver.aar"
if (-not (Test-Path $aar)) { Write-Host "Downloading AAR..."; Invoke-WebRequest "$base/v$ver/sherpa-onnx-$ver.aar" -OutFile $aar }
# Windows tar cannot decompress .bz2 (no bzip2 binary), so use Python or 7-Zip instead.
function Expand-Bz2($file, $outDir) {
  $py = Get-Command python -ErrorAction SilentlyContinue
  if (-not $py) { $py = Get-Command py -ErrorAction SilentlyContinue }
  if ($py) { & $py.Source -c "import tarfile,sys; tarfile.open(sys.argv[1]).extractall(sys.argv[2])" $file $outDir; return }
  $z = Get-Command 7z -ErrorAction SilentlyContinue
  if (-not $z -and (Test-Path "C:\Program Files\7-Zip\7z.exe")) { $z = Get-Command "C:\Program Files\7-Zip\7z.exe" }
  if ($z) {
    & $z.Source x -y "-o$outDir" $file | Out-Null
    $tar = Join-Path $outDir ([IO.Path]::GetFileNameWithoutExtension($file))
    & $z.Source x -y "-o$outDir" $tar | Out-Null
    return
  }
  throw "Need Python or 7-Zip to unpack .tar.bz2"
}
# The Ukrainian Piper model is a "text" model (Cyrillic letters are its tokens), but sherpa-onnx feeds it
# espeak IPA phonemes by default -> most phonemes are skipped and the speech is garbage.
# Appending these ONNX metadata entries (protobuf field 14, repeated) switches sherpa to its character frontend.
function Patch-UkModel($path) {
  $pairs = [ordered]@{ frontend = "characters"; add_blank = "1"; blank_id = "0"; bos_id = "1"; eos_id = "2"; use_eos_bos = "1" }
  $fs = [IO.File]::Open($path, [IO.FileMode]::Append)
  foreach ($k in $pairs.Keys) {
    $kb = [Text.Encoding]::UTF8.GetBytes($k); $vb = [Text.Encoding]::UTF8.GetBytes($pairs[$k])
    $entry = [byte[]](@(0x0A, $kb.Length) + $kb + @(0x12, $vb.Length) + $vb)
    $rec = [byte[]](@(0x72, $entry.Length) + $entry)
    $fs.Write($rec, 0, $rec.Length)
  }
  $fs.Close()
}
$models = @{ uk = "vits-piper-uk_UA-ukrainian_tts-medium"; en = "vits-piper-en_US-lessac-medium"; ru = "vits-piper-ru_RU-irina-medium" }
foreach ($k in $models.Keys) {
  $name = $models[$k]; $dst = "$root\app\src\main\assets\models\$k"
  if (Test-Path "$dst\model.onnx") {
    if ($k -eq "uk" -and -not (Test-Path "$dst\patched.txt")) {
      Write-Host "Patching existing uk model..."; Patch-UkModel "$dst\model.onnx"; Set-Content "$dst\patched.txt" "ok"
      Remove-Item "$dst\espeak-ng-data" -Recurse -Force -ErrorAction SilentlyContinue
    }
    continue
  }
  Write-Host "Downloading model $k..."
  $tmp = Join-Path $env:TEMP "$name.tar.bz2"
  Invoke-WebRequest "$base/tts-models/$name.tar.bz2" -OutFile $tmp
  Expand-Bz2 $tmp $env:TEMP
  if (-not (Test-Path "$env:TEMP\$name")) { throw "Extraction failed: install Python or 7-Zip and retry" }
  New-Item -ItemType Directory -Force $dst | Out-Null
  Copy-Item "$env:TEMP\$name\*" $dst -Recurse -Force
  $onnx = Get-ChildItem $dst -Filter *.onnx | Select-Object -First 1
  Rename-Item $onnx.FullName "model.onnx"
  if ($k -eq "uk") {
    Patch-UkModel "$dst\model.onnx"; Set-Content "$dst\patched.txt" "ok"
    Remove-Item "$dst\espeak-ng-data" -Recurse -Force -ErrorAction SilentlyContinue   # not used by the char frontend
  }
}
Write-Host "Done. Open the folder in Android Studio."
