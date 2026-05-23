# run_load_tests.ps1
# Automates the execution of JMeter load tests for the URL Shortener service.

$ErrorActionPreference = "Continue"

$toolsDir = "C:\Users\isa\.tools"
$jmeterBat = Join-Path $toolsDir "apache-jmeter-5.6.3\bin\jmeter.bat"
$jmxFile = "jmeter\UrlResolveTestPlan.jmx"
$logFile = "jmeter\results.csv"
$reportDir = "jmeter\report"

# 1. Verify JMeter installation
if (-not (Test-Path $jmeterBat)) {
    Write-Host "ERROR: JMeter not found at: $jmeterBat" -ForegroundColor Red
    Write-Host "Please run 'powershell -File jmeter\jmeter_setup.ps1' first!" -ForegroundColor Yellow
    exit 1
}

# 2. Port Auto-detection
$targetPort = 80 # Default Nginx Proxy
$targetHost = "localhost"

Write-Host "Checking server connectivity..." -ForegroundColor Cyan
$nginxUp = $false
$springUp = $false

try {
    $response = Invoke-WebRequest -Uri "http://localhost/country-search" -UseBasicParsing -TimeoutSec 2 -ErrorAction SilentlyContinue
    if ($response.StatusCode -eq 200) { $nginxUp = $true }
} catch {}

try {
    $response = Invoke-WebRequest -Uri "http://localhost:8080/actuator/health" -UseBasicParsing -TimeoutSec 2 -ErrorAction SilentlyContinue
    if ($response.StatusCode -eq 200) { $springUp = $true }
} catch {}

if ($nginxUp) {
    Write-Host "[DETECTED] Nginx proxy is UP on port 80. Testing through Nginx proxy." -ForegroundColor Green
    $targetPort = 80
} elseif ($springUp) {
    Write-Host "[DETECTED] Nginx proxy is DOWN, but Spring Boot is UP on port 8080. Testing backend directly." -ForegroundColor Yellow
    $targetPort = 8080
} else {
    Write-Host "[WARNING] Neither Nginx (port 80) nor Spring Boot (port 8080) responded to health check." -ForegroundColor Red
    Write-Host "Please start the infrastructure with: docker compose -f docker-compose-local.yml up -d" -ForegroundColor Yellow
    Write-Host "And start the Spring Boot app: .\mvnw.cmd spring-boot:run" -ForegroundColor Yellow
    Write-Host "Defaulting test targets to port 8080." -ForegroundColor Gray
    $targetPort = 8080
}

# 3. Clean up old test artifacts (JMeter fails if output files/folders exist)
Write-Host "Cleaning up old report files..." -ForegroundColor Cyan
if (Test-Path $logFile) {
    Remove-Item $logFile -Force | Out-Null
}
if (Test-Path $reportDir) {
    Remove-Item $reportDir -Recurse -Force | Out-Null
}

# 4. Generate unique run ID for RequestCoalescer test
$runId = "coalesce_run_" + (Get-Date -Format "yyyyMMdd_HHmmss")
Write-Host "Unique Run ID for coalescing test: $runId" -ForegroundColor Cyan

# 5. Execute JMeter in non-GUI (CLI) mode
Write-Host "==========================================================" -ForegroundColor Green
Write-Host "STARTING JMETER LOAD TESTS" -ForegroundColor Green
Write-Host "JMX Plan:    $jmxFile" -ForegroundColor Gray
Write-Host "Target URL:  http://${targetHost}:${targetPort}" -ForegroundColor Gray
Write-Host "Duration:    60 seconds" -ForegroundColor Gray
Write-Host "==========================================================" -ForegroundColor Green

$jmeterArgs = @(
    "-n",                     # Non-GUI mode
    "-t", $jmxFile,          # Test plan file
    "-l", $logFile,          # Result log file (CSV format)
    "-e",                     # Generate HTML Dashboard Report
    "-o", $reportDir,         # Report output directory
    "-JbaseUrl=$targetHost",  # Spring Boot Host
    "-Jport=$targetPort",     # Spring Boot Port
    "-JrunId=$runId",         # Unique Request Coalescing ID
    "-Jduration=60",          # Test duration in seconds
    "-JrampUp=10"             # Thread ramp-up time in seconds
)

# Run the process
$process = Start-Process -FilePath $jmeterBat -ArgumentList $jmeterArgs -NoNewWindow -PassThru -Wait

Write-Host ""
Write-Host "==========================================================" -ForegroundColor Green
Write-Host "LOAD TEST FINISHED" -ForegroundColor Green
Write-Host "==========================================================" -ForegroundColor Green

# 6. Basic Log-Based Analysis
if (Test-Path $logFile) {
    Write-Host "Analyzing log results..." -ForegroundColor Cyan
    
    $lines = Get-Content $logFile
    # Skip header
    $samples = $lines[1..($lines.Length-1)]
    
    $totalRequests = 0
    $successRequests = 0
    $totalLatency = 0
    $errors = 0
    
    foreach ($sample in $samples) {
        if ($sample.Trim() -eq "") { continue }
        $parts = $sample.Split(",")
        if ($parts.Length -gt 7) {
            $totalRequests++
            $lat = [int]$parts[1]
            $successVal = $parts[7]
            
            $totalLatency += $lat
            if ($successVal -eq "true") {
                $successRequests++
            } else {
                $errors++
            }
        }
    }
    
    if ($totalRequests -gt 0) {
        $avgLatency = [Math]::Round(($totalLatency / $totalRequests), 2)
        $successRate = [Math]::Round(($successRequests / $totalRequests * 100), 2)
        
        Write-Host "SUMMARY RESULTS FROM CSV LOG:" -ForegroundColor Yellow
        Write-Host "  - Total Requests:      $totalRequests" -ForegroundColor White
        Write-Host "  - Average Latency:     $avgLatency ms" -ForegroundColor White
        Write-Host "  - Success Rate:        $successRate %" -ForegroundColor White
        Write-Host "  - Error Count:         $errors" -ForegroundColor White
    } else {
        Write-Host "WARNING: No request logs found in results.csv." -ForegroundColor Yellow
    }

    # Request Coalescing Verification Tip
    Write-Host ""
    Write-Host "REQUEST COALESCING VERIFICATION:" -ForegroundColor Yellow
    Write-Host "  Please inspect your Spring Boot application terminal log." -ForegroundColor White
    Write-Host "  You should see EXACTLY ONE line stating:" -ForegroundColor White
    Write-Host "  --> Resolving shortCode from database: COALESCE_$runId" -ForegroundColor Green
    Write-Host "  Even though 50 concurrent requests hit the server at the exact same millisecond!" -ForegroundColor White
}

# 7. Provide report link
Write-Host ""
if (Test-Path $reportDir) {
    $absoluteReportPath = (Resolve-Path $reportDir).Path
    $dashboardFile = Join-Path $absoluteReportPath "index.html"
    Write-Host "Premium HTML Dashboard Report generated at:" -ForegroundColor Cyan
    Write-Host "  file:///$dashboardFile" -ForegroundColor Cyan
    Write-Host "  (Open index.html in any browser to see interactive graphs, latency, and percentiles)" -ForegroundColor Gray
}
