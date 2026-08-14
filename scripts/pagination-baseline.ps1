param(
    [string]$Container = "aicasetest-mysql",
    [string]$User = "root",
    [string]$Password = "",
    [string]$Database = "aicasetest",
    [string]$ProjectId = "P0000001"
)

# vP5: 大数据量分页基线——用 EXPLAIN 验证 test_cases 筛选/排序是否命中索引。
$ErrorActionPreference = "Stop"

$sql = @"
SELECT COUNT(*) AS total_cases FROM test_cases WHERE project_id = '$ProjectId';
EXPLAIN SELECT id, title FROM test_cases
  WHERE project_id = '$ProjectId'
  ORDER BY created_at LIMIT 20 OFFSET 1000;
EXPLAIN SELECT id, title FROM test_cases
  WHERE project_id = '$ProjectId'
    AND type = 'positive'
    AND execution_status = 'passed'
    AND review_status = 'approved'
  LIMIT 20;
"@

$sql | docker exec -i $Container sh -c "MYSQL_PWD='$Password' mysql -t -u$User $Database"
if ($LASTEXITCODE -ne 0) {
    throw "分页基线查询失败"
}
Write-Output "pagination baseline OK: 请检查 EXPLAIN 输出中的 key 字段（预期使用 idx_testcases_project_* 索引）"
