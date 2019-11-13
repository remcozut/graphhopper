#Creating logoutput and filenames
$LogFolder = ".\Log"
$LogFile = $LogFolder + "\Check_GraphHopper_" + (Get-Date -UFormat "%Y%m%d") + ".log"

.".\Write-Log.ps1"


write-log -LogOutput "Check started"  -Path $LogFile


$response = Invoke-WebRequest "http://localhost:8990/healthcheck" -UseBasicParsing


$restart = $FALSE



try {
$response = Invoke-WebRequest "http://localhost:8990/ping" -UseBasicParsing
} catch {
write-log -LogOutput "Ping Command statuscode"  -Path $LogFile
}
if ($response.StatusCode -ne 200) {
	write-log -LogOutput "Error Status statuscode 200"  -Path $LogFile
	$restart = $TRUE
} 

if ( $response.Content -like !"pong") {
	write-log -LogOutput "Ping FAILURE"  -Path $LogFile
	$restart = $TRUE
}



if ($response.StatusCode -ne 200) {
	write-log -LogOutput "Error Status statuscode 200"  -Path $LogFile
	$restart = $TRUE
} 

if ($response.Content -like "*""healthy"":true*") {
	write-log -LogOutput "Status not healthy"  -Path $LogFile
	$restart = $TRUE
}



if ($restart) {
	Send-MailMessage -From 'noreply@locatienet.com' -To 'Remco Zut <rzu@locatienet.com>' -Subject "restart GraphHopper $env:computername" -Priority High -SmtpServer 'rly.info.nl'
	write-log -LogOutput "restart service" -Path $LogFile
	Restart-Service -Name GraphHopper
}

