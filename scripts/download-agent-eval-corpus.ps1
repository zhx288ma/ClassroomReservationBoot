param(
    [string]$OutputDirectory = (Join-Path $PSScriptRoot "..\\test-data\\agent-corpus")
)

$ErrorActionPreference = "Stop"
New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null

# External reference material only. Do not treat these documents as this system's enforceable rules.
$documents = @(
    @{
        Name = "pku-seminar-room-policy.pdf"
        Url = "https://dean.pku.edu.cn/userfiles/upload/download/202210061500263346.pdf"
    },
    @{
        Name = "cumt-smart-study-room-notice.pdf"
        Url = "https://gjzx.cumt.edu.cn/__local/5/12/41/55FD9D26D45F633D207692F1AC4_866CE0F9_BE451.pdf?e=.pdf"
    },
    @{
        Name = "ncu-classroom-management-faq.pdf"
        Url = "https://video.ncu.edu.cn/docs/2024-03/368439ef35364dc7bcd86e94272d2e84.pdf"
    }
)

foreach ($document in $documents) {
    $target = Join-Path $OutputDirectory $document.Name
    Write-Host "Downloading $($document.Name)"
    try {
        Invoke-WebRequest -Uri $document.Url -OutFile $target
    } catch {
        # A public university site may move a file; keep the remaining evaluation corpus usable.
        Remove-Item -LiteralPath $target -Force -ErrorAction SilentlyContinue
        Write-Warning "Skipped $($document.Name): $($_.Exception.Message)"
    }
}

Write-Host "Download complete: $OutputDirectory"
