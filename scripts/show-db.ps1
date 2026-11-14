# Query and display NEXUS Chat MySQL Database tables
$mysqlPath = "C:\laragon\bin\mysql\mysql-8.4.3-winx64\bin\mysql.exe"
if (-not (Test-Path $mysqlPath)) {
    Write-Host "MySQL executable not found at $mysqlPath" -ForegroundColor Red
    exit 1
}

Write-Host "=========================================" -ForegroundColor Cyan
Write-Host "       NEXUS CHAT DATABASE VIEWER        " -ForegroundColor Yellow
Write-Host "=========================================" -ForegroundColor Cyan

Write-Host "`n[1] CHAT ROOMS (chat_rooms):" -ForegroundColor Green
& $mysqlPath -u root nexus_chat -e "SELECT room_id, room_name, description FROM chat_rooms;"

Write-Host "`n[2] USER PROFILES (user_profiles):" -ForegroundColor Green
& $mysqlPath -u root nexus_chat -e "SELECT username, display_name, status, last_seen_at FROM user_profiles;"

Write-Host "`n[3] CHAT MESSAGES (chat_messages):" -ForegroundColor Green
& $mysqlPath -u root nexus_chat -e "SELECT local_sequence, message_id, room_id, sender_name, SUBSTRING(content, 1, 40) AS content_preview, created_at FROM chat_messages ORDER BY local_sequence DESC LIMIT 20;"

Write-Host "`n[4] REPLICATION OUTBOX (replication_outbox):" -ForegroundColor Green
& $mysqlPath -u root nexus_chat -e "SELECT outbox_id, message_id, target_peer_id, status, attempt_count, updated_at FROM replication_outbox ORDER BY outbox_id DESC LIMIT 10;"

Write-Host "`n[TIP] You can also open HeidiSQL in Laragon to view the full graphical UI!" -ForegroundColor Cyan
