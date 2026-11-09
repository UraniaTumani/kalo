$ErrorActionPreference = 'Stop'

function Invoke-Docker {
    & docker @args
    if ($LASTEXITCODE -ne 0) {
        throw "Docker command '$($args[0])' failed (exit $LASTEXITCODE)."
    }
}

$runId = [guid]::NewGuid().ToString('N')
$container = "kalo-pg-restore-$runId"
$workDir = Join-Path ([IO.Path]::GetTempPath()) $container
$dump = Join-Path $workDir 'fixture.dump'
$started = $false

New-Item -ItemType Directory -Path $workDir | Out-Null
try {
    # No host ports, bind mounts, existing volumes, or repository .env files.
    Invoke-Docker run --detach --rm --name $container --env "POSTGRES_PASSWORD=$runId" postgres:17-alpine | Out-Null
    $started = $true
    $ready = $false
    for ($attempt = 0; $attempt -lt 60; $attempt++) {
        # Use TCP so the temporary initialization server cannot pass readiness.
        docker exec $container pg_isready -q -h 127.0.0.1 -U postgres
        if ($LASTEXITCODE -eq 0) {
            $ready = $true
            break
        }
        Start-Sleep -Seconds 1
    }
    if (-not $ready) { throw 'PostgreSQL did not become ready.' }

    Invoke-Docker exec $container createdb -U postgres fixture_source
    Invoke-Docker exec $container createdb -U postgres fixture_restored
    $fixture = @'
CREATE SCHEMA rehearsal;
CREATE TABLE rehearsal.companies (id integer PRIMARY KEY, name text NOT NULL);
CREATE TABLE rehearsal.rides (
    id integer GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    company_id integer NOT NULL REFERENCES rehearsal.companies(id),
    final_amount numeric(12,2) NOT NULL
);
INSERT INTO rehearsal.companies VALUES (1, 'Fixture Taxi');
INSERT INTO rehearsal.rides (company_id, final_amount) VALUES (1, 800.50), (1, 125.25);
'@
    Invoke-Docker exec $container psql -X -v ON_ERROR_STOP=1 -U postgres -d fixture_source -c $fixture | Out-Null
    Invoke-Docker exec $container pg_dump -U postgres -Fc -f /tmp/source.dump fixture_source
    Invoke-Docker cp "${container}:/tmp/source.dump" $dump
    if ((Get-Item -LiteralPath $dump).Length -le 0) { throw 'The backup file is empty.' }
    Invoke-Docker cp $dump "${container}:/tmp/restore.dump"
    Invoke-Docker exec $container pg_restore -U postgres --exit-on-error --single-transaction -d fixture_restored /tmp/restore.dump

    $query = 'SELECT r.id, c.name, r.final_amount FROM rehearsal.rides r JOIN rehearsal.companies c ON c.id = r.company_id ORDER BY r.id'
    $restored = @(Invoke-Docker exec $container psql -X -v ON_ERROR_STOP=1 -U postgres -d fixture_restored -At -c $query)
    if (($restored -join ';') -cne '1|Fixture Taxi|800.50;2|Fixture Taxi|125.25') {
        throw 'The restored rows do not match the original fixture.'
    }
    $insert = 'INSERT INTO rehearsal.rides (company_id, final_amount) VALUES (1, 50.00) RETURNING id'
    $nextId = @(Invoke-Docker exec $container psql -X -qAt -v ON_ERROR_STOP=1 -U postgres -d fixture_restored -c $insert)
    if (($nextId -join '') -ne '3') { throw 'The restored identity sequence did not resume at 3.' }
} finally {
    try {
        if ($started) { Invoke-Docker rm --force --volumes $container | Out-Null }
    } finally {
        Remove-Item -LiteralPath $workDir -Recurse -Force
    }
}

Write-Output 'PASS: PostgreSQL 17 backup restored matching rows and a working identity sequence.'
