# jmeter_setup.ps1
# Automates downloading and extracting Apache JMeter 5.6.3 in a portable way.

$ErrorActionPreference = "Stop"

$toolsDir = "C:\Users\isa\.tools"
$jmeterVersion = "5.6.3"
$jmeterDir = Join-Path $toolsDir "apache-jmeter-$jmeterVersion"
$zipFile = Join-Path $toolsDir "apache-jmeter-$jmeterVersion.zip"

# Ensure tools directory exists
if (-not (Test-Path $toolsDir)) {
    Write-Host "Creating directory: $toolsDir..." -ForegroundColor Cyan
    New-Item -ItemType Directory -Path $toolsDir | Out-Null
}

# Check if JMeter is already installed
if (Test-Path (Join-Path $jmeterDir "bin\jmeter.bat")) {
    Write-Host "Apache JMeter $jmeterVersion is already installed at: $jmeterDir" -ForegroundColor Green
    exit 0
}

# Download URLs
$urls = @(
    "https://archive.apache.org/dist/jmeter/binaries/apache-jmeter-$jmeterVersion.zip",
    "https://dlcdn.apache.org//jmeter/binaries/apache-jmeter-$jmeterVersion.zip"
)

$downloaded = $false
foreach ($url in $urls) {
    try {
        Write-Host "Attempting to download JMeter from: $url..." -ForegroundColor Cyan
        [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
        
        # Using a ProgressPreference silent to speed up download
        $oldProgress = $ProgressPreference
        $ProgressPreference = 'SilentlyContinue'
        Invoke-WebRequest -Uri $url -OutFile $zipFile -TimeoutSec 180
        $ProgressPreference = $oldProgress
        
        if (Test-Path $zipFile) {
            $downloaded = $true
            Write-Host "Downloaded successfully!" -ForegroundColor Green
            break
        }
    } catch {
        Write-Host "Download from $url failed: $_" -ForegroundColor Yellow
    }
}

if (-not $downloaded) {
    Write-Host "ERROR: Failed to download Apache JMeter from all sources." -ForegroundColor Red
    exit 1
}

# Extract ZIP
try {
    Write-Host "Extracting JMeter to: $toolsDir... This might take a moment..." -ForegroundColor Cyan
    # Use Expand-Archive
    Expand-Archive -Path $zipFile -DestinationPath $toolsDir -Force
    Write-Host "Extraction complete!" -ForegroundColor Green
} catch {
    Write-Host "ERROR: Extraction failed: $_" -ForegroundColor Red
    exit 1
} finally {
    # Cleanup ZIP
    if (Test-Path $zipFile) {
        Write-Host "Cleaning up temporary zip file..." -ForegroundColor Gray
        Remove-Item $zipFile -Force
    }
}

# Verify installation
$jmeterBat = Join-Path $jmeterDir "bin\jmeter.bat"
if (Test-Path $jmeterBat) {
    Write-Host "Apache JMeter $jmeterVersion installed successfully!" -ForegroundColor Green
    Write-Host "Location: $jmeterDir" -ForegroundColor Green
} else {
    Write-Host "ERROR: Verification failed. jmeter.bat not found at: $jmeterBat" -ForegroundColor Red
    exit 1
}
