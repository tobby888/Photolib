param(
    [string]$BaseUrl = "http://127.0.0.1:18080/api/v1",
    [string]$AdminUsername = "qa_admin",
    [string]$AdminInitialPassword = "QaInit-2026!",
    [string]$ImagePath = (Join-Path $PSScriptRoot "..\Diagram.jpg"),
    [string]$ArtifactDirectory = (Join-Path $PSScriptRoot "..\.qa\artifacts")
)

$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.IO.Compression.FileSystem
$results = [System.Collections.Generic.List[object]]::new()

function Add-Result([string]$Id, [bool]$Passed, [string]$Detail) {
    $results.Add([pscustomobject]@{ id = $Id; passed = $Passed; detail = $Detail })
    $label = if ($Passed) { "PASS" } else { "FAIL" }
    Write-Host "[$label] $Id - $Detail"
}

function Assert-That([string]$Id, [bool]$Condition, [string]$Detail) {
    Add-Result $Id $Condition $Detail
}

function Invoke-Api(
    [string]$Method,
    [string]$Path,
    [string]$Token,
    $Body = $null,
    [Microsoft.PowerShell.Commands.WebRequestSession]$WebSession = $null
) {
    $headers = @{}
    if ($Token) { $headers.Authorization = "Bearer $Token" }
    $parameters = @{
        Uri = "$BaseUrl$Path"
        Method = $Method
        Headers = $headers
        ContentType = "application/json; charset=utf-8"
    }
    if ($null -ne $Body) {
        $parameters.Body = ($Body | ConvertTo-Json -Depth 12 -Compress)
    }
    if ($WebSession) { $parameters.WebSession = $WebSession }
    (Invoke-RestMethod @parameters).data
}

function Expect-Status(
    [string]$Id,
    [int]$Status,
    [string]$Method,
    [string]$Path,
    [string]$Token,
    $Body = $null
) {
    try {
        Invoke-Api $Method $Path $Token $Body | Out-Null
        Add-Result $Id $false "预期 HTTP $Status，实际请求成功"
    } catch {
        $actual = 0
        if ($_.Exception.Response -and $_.Exception.Response.StatusCode) {
            $actual = [int]$_.Exception.Response.StatusCode
        }
        Add-Result $Id ($actual -eq $Status) "预期 HTTP $Status，实际 HTTP $actual"
    }
}

function Login([string]$Username, [string]$Password) {
    Invoke-Api "POST" "/auth/login" "" @{ username = $Username; password = $Password }
}

function Complete-InitialPassword([string]$Username, [string]$InitialPassword, [string]$NewPassword) {
    $login = Login $Username $InitialPassword
    $changed = Invoke-Api "PUT" "/auth/initial-password" $login.accessToken @{
        initialPassword = $InitialPassword
        newPassword = $NewPassword
    }
    $changed.accessToken
}

function Wait-Until([scriptblock]$Action, [scriptblock]$Done, [int]$Attempts = 30) {
    $last = $null
    for ($i = 0; $i -lt $Attempts; $i++) {
        $last = & $Action
        if (& $Done $last) { return $last }
        Start-Sleep -Milliseconds 300
    }
    $last
}

New-Item -ItemType Directory -Force -Path $ArtifactDirectory | Out-Null

try {
    $health = Invoke-RestMethod -Uri "$BaseUrl/actuator/health"
    Assert-That "INIT-01" ($health.status -eq "UP") "隔离服务健康状态为 $($health.status)"

    Expect-Status "INIT-03" 401 "POST" "/auth/login" "" @{
        username = $AdminUsername
        password = "definitely-wrong"
    }

    $adminLogin = Login $AdminUsername $AdminInitialPassword
    Assert-That "INIT-04" ($adminLogin.mustChangePassword -eq $true -and $adminLogin.user.role -eq "ADMIN") "初始化管理员登录并被要求首次改密"
    Expect-Status "INIT-05" 401 "PUT" "/auth/initial-password" $adminLogin.accessToken @{
        initialPassword = "wrong-initial"
        newPassword = "QaAdminNew-2026!"
    }
    $adminChanged = Invoke-Api "PUT" "/auth/initial-password" $adminLogin.accessToken @{
        initialPassword = $AdminInitialPassword
        newPassword = "QaAdminNew-2026!"
    }
    $adminToken = $adminChanged.accessToken
    Assert-That "INIT-06" ($adminChanged.mustChangePassword -eq $false) "管理员首次改密成功"
    Expect-Status "INIT-07" 409 "PUT" "/auth/initial-password" $adminToken @{
        initialPassword = $AdminInitialPassword
        newPassword = "AnotherAdmin-2026!"
    }
    Expect-Status "INIT-10" 401 "GET" "/users" ""

    $initialUsers = Invoke-Api "GET" "/users?page=1&pageSize=20" $adminToken
    Assert-That "INIT-02" ($initialUsers.total -eq 1 -and $initialUsers.items[0].role -eq "ADMIN") "空库初始化后仅有一个管理员"

    $campusA = Invoke-Api "POST" "/campuses" $adminToken @{ code = "QA-A"; name = "QA测试校区A" }
    $campusB = Invoke-Api "POST" "/campuses" $adminToken @{ code = "QA-B"; name = "QA测试校区B" }
    Assert-That "ACC-01" ($campusA.enabled -and $campusA.code -eq "QA-A") "创建 QA-A 校区成功"
    Expect-Status "ACC-02" 409 "POST" "/campuses" $adminToken @{ code = "QA-A"; name = "重复校区" }
    Expect-Status "ACC-05" 400 "POST" "/users" $adminToken @{
        username = "qa_no_campus"; displayName = "无校区负责人"; role = "CAMPUS_MANAGER"
    }

    $ministerCreated = Invoke-Api "POST" "/users" $adminToken @{
        username = "qa_minister"; displayName = "QA部长"; role = "MINISTER"; email = "qa-minister@example.test"
    }
    $campusCreated = Invoke-Api "POST" "/users" $adminToken @{
        username = "qa_campus_a"; displayName = "QA校区负责人A"; role = "CAMPUS_MANAGER"
        campusId = $campusA.id; email = "qa-campus-a@example.test"
    }
    $campusBCreated = Invoke-Api "POST" "/users" $adminToken @{
        username = "qa_campus_b"; displayName = "QA校区负责人B"; role = "CAMPUS_MANAGER"
        campusId = $campusB.id; email = "qa-campus-b@example.test"
    }
    Assert-That "ACC-03" ($ministerCreated.user.role -eq "MINISTER" -and $ministerCreated.initialPassword) "创建部长并返回一次性初始密码"
    Assert-That "ACC-04" ($campusCreated.user.role -eq "CAMPUS_MANAGER" -and $campusCreated.user.campusId -eq $campusA.id) "创建并关联 QA-A 校区负责人"
    Expect-Status "ACC-07" 400 "POST" "/users" $adminToken @{
        username = "x"; displayName = "非法账号"; role = "MINISTER"; email = "bad-email"
    }

    $ministerToken = Complete-InitialPassword "qa_minister" $ministerCreated.initialPassword "QaMinister-2026!"
    $campusToken = Complete-InitialPassword "qa_campus_a" $campusCreated.initialPassword "QaCampusA-2026!"
    $campusBToken = Complete-InitialPassword "qa_campus_b" $campusBCreated.initialPassword "QaCampusB-2026!"
    Assert-That "ACC-08" ($ministerToken -and $campusToken -and $campusBToken) "部长与校区负责人均完成首次改密"
    Expect-Status "ACC-09" 403 "POST" "/users" $ministerToken @{
        username = "qa_forbidden"; displayName = "越权"; role = "MINISTER"
    }
    Expect-Status "ACC-10" 403 "GET" "/audit-logs" $campusToken
    Expect-Status "ACC-13" 409 "POST" "/users/$($adminChanged.user.id)/disable" $adminToken

    $project = Invoke-Api "POST" "/projects" $ministerToken @{
        title = "QA-20260702-全流程项目"; description = "自动化全流程测试"; status = "DRAFT"
    }
    Assert-That "FLOW-01" ($project.status -eq "DRAFT") "部长创建项目草稿"
    $project = Invoke-Api "GET" "/projects/$($project.id)" $ministerToken
    Expect-Status "FLOW-03" 400 "POST" "/projects" $ministerToken @{
        title = "非法初态"; description = ""; status = "COMPLETED"
    }
    $project = Invoke-Api "PUT" "/projects/$($project.id)" $ministerToken @{
        title = "QA-20260702-全流程项目"; description = "已更新的测试说明"; version = $project.version
    }
    $project = Invoke-Api "POST" "/projects/$($project.id)/status" $ministerToken @{
        status = "ACTIVE"; version = $project.version
    }
    Assert-That "FLOW-02" ($project.status -eq "ACTIVE") "项目 DRAFT→ACTIVE"

    $deadline = (Get-Date).AddDays(7).ToString("yyyy-MM-ddTHH:mm:ss")
    $request = Invoke-Api "POST" "/projects/$($project.id)/requests" $ministerToken @{
        title = "QA-A 校园活动图片需求"; description = "完整流程测试需求"
        campusId = $campusA.id; requiredCount = 1; deadline = $deadline
    }
    Assert-That "FLOW-04" ($request.status -eq "DRAFT" -and $request.campusId -eq $campusA.id) "创建 QA-A 需求草稿"
    $request = Invoke-Api "GET" "/requests/$($request.id)" $ministerToken
    Expect-Status "FLOW-05" 400 "POST" "/projects/$($project.id)/requests" $ministerToken @{
        title = ""; description = ""; campusId = $campusA.id; requiredCount = 0
        deadline = (Get-Date).AddDays(-1).ToString("yyyy-MM-ddTHH:mm:ss")
    }
    $request = Invoke-Api "POST" "/requests/$($request.id)/publish" $ministerToken @{ version = $request.version }
    Assert-That "FLOW-06" ($request.status -eq "PUBLISHED") "需求 DRAFT→PUBLISHED"
    Expect-Status "FLOW-09" 403 "POST" "/requests/$($request.id)/accept" $campusBToken
    $request = Invoke-Api "POST" "/requests/$($request.id)/accept" $campusToken
    Assert-That "FLOW-07" ($request.status -eq "ACCEPTED") "QA-A 负责人接受需求"
    Expect-Status "FLOW-08" 409 "POST" "/requests/$($request.id)/accept" $campusToken
    Expect-Status "FLOW-13" 409 "POST" "/requests/$($request.id)/submit" $campusToken @{ version = $request.version }

    Expect-Status "WORK-02A" 400 "POST" "/requests/$($request.id)/worklogs" $campusToken @{
        workDate = (Get-Date).ToString("yyyy-MM-dd"); memberName = "测试成员"; memberStudentId = "QA2026001"
        shootingMinutes = 0; retouchingMinutes = 0
        remark = "非法零工时"; status = "DRAFT"
    }
    Expect-Status "WORK-02B" 403 "POST" "/requests/$($request.id)/worklogs" $campusBToken @{
        workDate = (Get-Date).ToString("yyyy-MM-dd"); memberName = "测试成员"; memberStudentId = "QA2026001"
        shootingMinutes = 10; retouchingMinutes = 0
        remark = "非参与人"; status = "DRAFT"
    }

    $image = Get-Item $ImagePath
    $sha = (Get-FileHash -Algorithm SHA256 $ImagePath).Hash.ToLowerInvariant()
    $takenAt = (Get-Date).AddDays(-1).ToString("yyyy-MM-ddTHH:mm:ss")
    # 上传拍摄者强制来自校区通讯录：先由负责人建立通讯录成员，用其 id 作为 photographerContactId
    $photographer = Invoke-Api "POST" "/campus-members" $campusToken @{ name = "测试摄影师"; studentId = "QA2026001" }
    Assert-That "DIR-01" ($photographer.id -and $photographer.studentId -eq "QA2026001") "负责人向校区通讯录添加拍摄者"
    $ticket = Invoke-Api "POST" "/photos/upload-tickets" $campusToken @{
        requestId = $request.id; projectId = $project.id; fileName = $image.Name
        contentType = "image/jpeg"; size = $image.Length; sha256 = $sha
        photographerContactId = $photographer.id; takenAt = $takenAt
    }
    Assert-That "OSS-01" ($ticket.photoId -and $ticket.uploadUrl -and $ticket.method -eq "PUT") "签发单图上传票据"
    Expect-Status "OSS-04" 415 "POST" "/photos/upload-tickets" $campusToken @{
        requestId = $request.id; projectId = $project.id; fileName = "bad.gif"
        contentType = "image/gif"; size = 10; sha256 = ("0" * 64)
        photographerContactId = $photographer.id; takenAt = $takenAt
    }
    Expect-Status "OSS-05" 403 "POST" "/photos/upload-tickets" $campusBToken @{
        requestId = $request.id; projectId = $project.id; fileName = $image.Name
        contentType = "image/jpeg"; size = $image.Length; sha256 = $sha
        photographerContactId = $photographer.id; takenAt = $takenAt
    }
    Invoke-WebRequest -UseBasicParsing -Method Put -Uri $ticket.uploadUrl -InFile $ImagePath -ContentType $ticket.contentType | Out-Null
    $photo = Invoke-Api "POST" "/photos/$($ticket.photoId)/complete-upload" $campusToken @{
        title = "QA活动全景"; description = "测试图片"; tags = @("QA", "活动")
    }
    $photo = Wait-Until { Invoke-Api "GET" "/photos/$($ticket.photoId)" $campusToken } { param($value) $value.status -in @("AVAILABLE", "FAILED") }
    Assert-That "OSS-02" ($photo.status -eq "AVAILABLE" -and $photo.width -gt 0 -and $photo.height -gt 0) "上传、校验、异步处理后图片可用"
    $download = Invoke-Api "POST" "/photos/$($photo.id)/download-url" $campusToken
    $downloadPath = Join-Path $ArtifactDirectory "single-photo.jpg"
    Invoke-WebRequest -UseBasicParsing -Uri $download.downloadUrl -OutFile $downloadPath
    $downloadHash = (Get-FileHash -Algorithm SHA256 $downloadPath).Hash.ToLowerInvariant()
    Assert-That "OSS-08" ($downloadHash -eq $sha) "原图下载成功且 SHA-256 一致"

    $worklog = Invoke-Api "POST" "/requests/$($request.id)/worklogs" $campusToken @{
        workDate = (Get-Date).ToString("yyyy-MM-dd"); memberName = "测试成员"; memberStudentId = "QA2026001"
        shootingMinutes = 60; retouchingMinutes = 30
        remark = "初次填报"; status = "DRAFT"
    }
    Assert-That "WORK-01" ($worklog.status -eq "DRAFT") "新增 60+30 分钟草稿工时"
    $worklog = (Invoke-Api "GET" "/worklogs?requestId=$($request.id)" $campusToken).items |
        Where-Object { $_.id -eq $worklog.id } | Select-Object -First 1
    $worklog = Invoke-Api "POST" "/worklogs/$($worklog.id)/submit" $campusToken @{ version = $worklog.version }
    Assert-That "WORK-04" ($worklog.status -eq "SUBMITTED") "工时提交"
    Expect-Status "WORK-05" 409 "PUT" "/worklogs/$($worklog.id)?version=$($worklog.version)" $campusToken @{
        workDate = $worklog.workDate; memberName = $worklog.memberName; memberStudentId = $worklog.memberStudentId
        shootingMinutes = 70; retouchingMinutes = 30; remark = "不应成功"; status = "DRAFT"
    }
    $worklog = Invoke-Api "POST" "/worklogs/$($worklog.id)/reject" $ministerToken @{
        reason = "请重新核对工时"; version = $worklog.version
    }
    Assert-That "WORK-06" ($worklog.status -eq "REJECTED" -and $worklog.rejectReason) "部长退回工时并记录原因"
    $worklog = Invoke-Api "PUT" "/worklogs/$($worklog.id)?version=$($worklog.version)" $campusToken @{
        workDate = $worklog.workDate; memberName = $worklog.memberName; memberStudentId = $worklog.memberStudentId
        shootingMinutes = 75; retouchingMinutes = 45
        remark = "复核后修改"; status = "DRAFT"
    }
    $worklog = Invoke-Api "POST" "/worklogs/$($worklog.id)/submit" $campusToken @{ version = $worklog.version }
    Assert-That "WORK-07" ($worklog.status -eq "SUBMITTED" -and $worklog.shootingMinutes -eq 75 -and $worklog.retouchingMinutes -eq 45) "退回工时修改后再次提交"
    $worklog = Invoke-Api "POST" "/worklogs/$($worklog.id)/confirm" $ministerToken @{ version = $worklog.version }
    Assert-That "WORK-08" ($worklog.status -eq "CONFIRMED") "部长确认工时"
    Expect-Status "WORK-09" 409 "POST" "/worklogs/$($worklog.id)/confirm" $ministerToken @{ version = $worklog.version }

    $request = Invoke-Api "GET" "/requests/$($request.id)" $campusToken
    $request = Invoke-Api "POST" "/requests/$($request.id)/submit" $campusToken @{ version = $request.version }
    Assert-That "FLOW-12" ($request.status -eq "SUBMITTED") "存在可用图片后提交需求"
    $request = Invoke-Api "POST" "/requests/$($request.id)/complete" $ministerToken @{ version = $request.version }
    Assert-That "FLOW-14" ($request.status -eq "COMPLETED") "部长完成需求"

    $adoptions = Invoke-Api "POST" "/projects/$($project.id)/adoptions" $ministerToken @{
        photoIds = @($photo.id); remark = "用于 QA 全流程"
    }
    Assert-That "ADOPT-01" (@($adoptions).Count -eq 1 -and $adoptions[0].photoId -eq $photo.id) "创建图片采用记录"
    Expect-Status "ADOPT-03" 409 "POST" "/projects/$($project.id)/adoptions" $ministerToken @{
        photoIds = @($photo.id); remark = "重复采用"
    }
    Expect-Status "ADOPT-04" 403 "POST" "/projects/$($project.id)/adoptions" $campusToken @{
        photoIds = @($photo.id); remark = "越权采用"
    }
    $ranking = Invoke-Api "GET" "/statistics/adoptions/ranking?projectId=$($project.id)" $ministerToken
    Assert-That "ADOPT-06" ($ranking[0].photographerStudentId -eq "QA2026001" -and $ranking[0].adoptedCount -eq 1) "采用排行计数为 1"
    $photoAfterAdoption = Invoke-Api "GET" "/photos/$($photo.id)" $ministerToken
    $hasAdoptionMarker = ($photoAfterAdoption.PSObject.Properties.Name -contains "adoptionCount") -or
        ($photoAfterAdoption.PSObject.Properties.Name -contains "adopted")
    Assert-That "ADOPT-02" $hasAdoptionMarker "图片详情应返回 adopted/adoptionCount 标记"
    Expect-Status "OSS-11" 409 "DELETE" "/photos/$($photo.id)" $campusToken

    $members = Invoke-Api "GET" "/statistics/members?projectId=$($project.id)&userId=$($campusCreated.user.id)" $ministerToken
    Assert-That "WORK-12" (@($members).Count -eq 1 -and $members[0].shootingMinutes -eq 75 -and $members[0].retouchingMinutes -eq 45 -and $members[0].totalMinutes -eq 120) "统计仅计入已确认工时，总计 120 分钟"
    Expect-Status "EXPORT-04" 403 "POST" "/statistics/members/export" $campusToken @{
        projectId = $project.id; format = "XLSX"
    }

    $exportJob = Invoke-Api "POST" "/statistics/members/export" $ministerToken @{
        from = (Get-Date).AddDays(-30).ToString("yyyy-MM-dd")
        to = (Get-Date).AddDays(1).ToString("yyyy-MM-dd")
        projectId = $project.id; campusId = $campusA.id; format = "XLSX"
    }
    $exportView = Wait-Until { Invoke-Api "GET" "/export-jobs/$($exportJob.id)" $ministerToken } { param($value) $value.job.status -in @("SUCCEEDED", "FAILED") }
    Assert-That "EXPORT-01" ($exportView.job.status -eq "SUCCEEDED") "成员统计异步任务完成"
    $xlsxPath = Join-Path $ArtifactDirectory "member-statistics.xlsx"
    if ($exportView.downloadUrl) { Invoke-WebRequest -UseBasicParsing -Uri $exportView.downloadUrl -OutFile $xlsxPath }
    Assert-That "EXPORT-02" (Test-Path $xlsxPath) "XLSX 已下载，内容由后续工件检查验证"

    $zipJob = Invoke-Api "POST" "/photos/batch-download" $ministerToken @{
        photoIds = @($photo.id); purpose = "QA 验证"
    }
    $zipView = Wait-Until { Invoke-Api "GET" "/export-jobs/$($zipJob.id)" $ministerToken } { param($value) $value.job.status -in @("SUCCEEDED", "FAILED") }
    $zipPath = Join-Path $ArtifactDirectory "photos.zip"
    if ($zipView.downloadUrl) { Invoke-WebRequest -UseBasicParsing -Uri $zipView.downloadUrl -OutFile $zipPath }
    $zipEntries = if (Test-Path $zipPath) {
        [System.IO.Compression.ZipFile]::OpenRead($zipPath).Entries
    } else { @() }
    Assert-That "EXPORT-06" ($zipView.job.status -eq "SUCCEEDED" -and $zipEntries.Count -eq 1) "图片 ZIP 任务成功且包含 1 个文件"
    Expect-Status "EXPORT-07" 400 "POST" "/photos/batch-download" $ministerToken @{ photoIds = @(); purpose = "空列表" }

    $campusNotifications = Invoke-Api "GET" "/notifications" $campusToken
    $eventTypes = @($campusNotifications | ForEach-Object { $_.eventType })
    Assert-That "NOTIFY-01" ($eventTypes -contains "ACCOUNT_CREATED") "账号创建站内通知存在"
    Assert-That "NOTIFY-02" ($eventTypes -contains "REQUEST_PUBLISHED") "需求发布站内通知存在"
    Assert-That "NOTIFY-04" (($eventTypes -contains "WORKLOG_REJECTED") -and ($eventTypes -contains "WORKLOG_CONFIRMED")) "工时退回与确认通知存在"
    $unreadBefore = Invoke-Api "GET" "/notifications/unread-count" $campusToken
    if ($campusNotifications.Count -gt 0) {
        Invoke-Api "POST" "/notifications/$($campusNotifications[0].id)/read" $campusToken | Out-Null
    }
    Invoke-Api "POST" "/notifications/read-all" $campusToken | Out-Null
    $unreadAfter = Invoke-Api "GET" "/notifications/unread-count" $campusToken
    Assert-That "NOTIFY-05" ($unreadBefore.count -gt 0 -and $unreadAfter.count -eq 0) "单条/全部已读后未读数归零"
    $mailSettings = Invoke-Api "GET" "/notification-logs/settings" $adminToken
    Assert-That "NOTIFY-07" ($mailSettings.environmentEnabled -eq $false -and $mailSettings.effectiveEnabled -eq $false) "部署级邮件关闭，站内通知仍正常"
    Expect-Status "NOTIFY-08" 403 "GET" "/notification-logs/settings" $ministerToken

    $audit = Invoke-Api "GET" "/audit-logs" $adminToken
    Assert-That "AUDIT-01" ($audit.Count -gt 10) "管理员可查看测试期间写操作审计"
    Expect-Status "AUDIT-02" 403 "GET" "/audit-logs" $ministerToken

    $photo = Invoke-Api "POST" "/photos/$($photo.id)/archive" $ministerToken
    $photo = Invoke-Api "POST" "/photos/$($photo.id)/restore" $ministerToken
    Assert-That "OSS-10" ($photo.status -eq "AVAILABLE") "图片归档后可恢复"

    $project = Invoke-Api "GET" "/projects/$($project.id)" $ministerToken
    $project = Invoke-Api "POST" "/projects/$($project.id)/status" $ministerToken @{
        status = "COMPLETED"; version = $project.version
    }
    Assert-That "FLOW-16" ($project.status -eq "COMPLETED") "项目完成并进入锁定状态"
    Expect-Status "ADOPT-08" 409 "DELETE" "/projects/$($project.id)/adoptions/$($adoptions[0].id)" $ministerToken
    Expect-Status "FLOW-17A" 403 "POST" "/projects/$($project.id)/reopen" $ministerToken @{
        reason = "部长不应有权限"; version = $project.version
    }
    $project = Invoke-Api "POST" "/projects/$($project.id)/reopen" $adminToken @{
        reason = "QA 验证重新开放"; version = $project.version
    }
    Assert-That "FLOW-17B" ($project.status -eq "ACTIVE") "管理员重新开放已完成项目"
} catch {
    Add-Result "RUN-FATAL" $false $_.Exception.Message
}

$summary = [pscustomobject]@{
    executedAt = (Get-Date).ToString("o")
    baseUrl = $BaseUrl
    passed = @($results | Where-Object passed).Count
    failed = @($results | Where-Object { -not $_.passed }).Count
    results = $results
}
$summary | ConvertTo-Json -Depth 8 | Set-Content -Encoding UTF8 (Join-Path $ArtifactDirectory "api-test-results.json")
Write-Host ""
Write-Host "SUMMARY passed=$($summary.passed) failed=$($summary.failed)"
if ($summary.failed -gt 0) { exit 1 }
