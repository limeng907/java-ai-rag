param(
    [Parameter(Mandatory)] [ValidateRange(1,2147483647)] [long] $UserId,
    [ValidatePattern('^[a-zA-Z0-9_-]+$')] [string] $DatabaseContainer = 'my_pgvector'
)

$ErrorActionPreference = 'Stop'
$sql = "UPDATE app_user SET role='ADMIN' WHERE id=$UserId RETURNING id,username,role;"
& docker exec $DatabaseContainer psql -U postgres -d java_rag -v ON_ERROR_STOP=1 -c $sql
if ($LASTEXITCODE -ne 0) { throw 'Administrator role update failed.' }
