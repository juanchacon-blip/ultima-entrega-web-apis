<#
  Despliega los microservicios en Kubernetes usando las imágenes publicadas en GHCR.
  Lo ejecuta el job "deploy" del workflow en el runner Windows propio, pero también
  se puede correr a mano:

    ./scripts/deploy-k8s.ps1 -Registry ghcr.io/<usuario-en-minusculas> -Tag <sha-o-latest>

  Compatible con Windows PowerShell 5.1 y PowerShell 7.
#>
param(
    [Parameter(Mandatory = $true)][string]$Registry,
    [Parameter(Mandatory = $true)][string]$Tag,
    [string]$Namespace = "microservicios",
    [string]$JwtPrivateKey = $env:JWT_PRIVATE_KEY,
    [string]$JwtPublicKey = $env:JWT_PUBLIC_KEY,
    [string]$GhcrUser = "",
    [string]$GhcrToken = ""
)

$ErrorActionPreference = "Stop"
Set-Location (Join-Path $PSScriptRoot "..")

function Invoke-Kubectl {
    & kubectl @args
    if ($LASTEXITCODE -ne 0) { throw "kubectl $($args -join ' ') falló (código $LASTEXITCODE)" }
}

# Aplica un manifiesto reemplazando la imagen local por la de GHCR.
function Apply-Manifest([string]$File) {
    $content = Get-Content -Raw -Path $File
    $content = $content -replace 'image: (auth-service|task-service|frontend):1\.0\.0', ('image: ' + $Registry + '/$1:' + $Tag)
    $content | & kubectl apply -f -
    if ($LASTEXITCODE -ne 0) { throw "kubectl apply falló para $File" }
}

# 1) Namespace
Invoke-Kubectl apply -f k8s/00-namespace.yaml

# 2) Llaves RSA -> Secret jwt-keys
$tmp = Join-Path ([System.IO.Path]::GetTempPath()) ("jwt-" + [guid]::NewGuid())
New-Item -ItemType Directory -Path $tmp | Out-Null
try {
    $priv = Join-Path $tmp "private.pem"
    $pub = Join-Path $tmp "public.pem"
    $existing = & kubectl get secret jwt-keys -n $Namespace --ignore-not-found -o name

    if ($JwtPrivateKey -and $JwtPublicKey) {
        # Llaves provistas por GitHub Secrets (JWT_PRIVATE_KEY / JWT_PUBLIC_KEY)
        [System.IO.File]::WriteAllText($priv, $JwtPrivateKey)
        [System.IO.File]::WriteAllText($pub, $JwtPublicKey)
    }
    elseif (-not $existing) {
        # Primera vez y sin secrets: se generan en el runner (requiere openssl, incluido en Git for Windows)
        if (-not (Get-Command openssl -ErrorAction SilentlyContinue)) {
            throw "No hay llaves JWT: define los secrets JWT_PRIVATE_KEY y JWT_PUBLIC_KEY o instala openssl en el runner."
        }
        & openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out $priv
        if ($LASTEXITCODE -ne 0) { throw "openssl genpkey falló" }
        & openssl rsa -in $priv -pubout -out $pub
        if ($LASTEXITCODE -ne 0) { throw "openssl rsa falló" }
    }

    if (Test-Path $priv) {
        & kubectl create secret generic jwt-keys -n $Namespace `
            --from-file=private.pem=$priv --from-file=public.pem=$pub `
            --dry-run=client -o yaml | & kubectl apply -f -
        if ($LASTEXITCODE -ne 0) { throw "No se pudo crear el secret jwt-keys" }
    }
    else {
        Write-Host "Se conserva el secret jwt-keys existente."
    }
}
finally {
    Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue
}

# 3) Secret opcional para descargar imágenes privadas de GHCR
if ($GhcrToken) {
    & kubectl create secret docker-registry ghcr-pull -n $Namespace `
        --docker-server=ghcr.io --docker-username=$GhcrUser --docker-password=$GhcrToken `
        --dry-run=client -o yaml | & kubectl apply -f -
    if ($LASTEXITCODE -ne 0) { throw "No se pudo crear el secret ghcr-pull" }
}

# 4) Recursos, en orden
Invoke-Kubectl apply -f k8s/01-secrets.yaml
Invoke-Kubectl apply -f k8s/02-postgres.yaml
Invoke-Kubectl rollout status deployment/postgres -n $Namespace --timeout=180s

Apply-Manifest "k8s/03-auth-service.yaml"
Apply-Manifest "k8s/04-task-service.yaml"
Invoke-Kubectl rollout status deployment/auth-service -n $Namespace --timeout=300s
Invoke-Kubectl rollout status deployment/task-service -n $Namespace --timeout=300s

Apply-Manifest "k8s/05-frontend.yaml"
Invoke-Kubectl rollout status deployment/frontend -n $Namespace --timeout=180s

Invoke-Kubectl get pods,svc -n $Namespace
Write-Host "Despliegue completo: $Registry/*:$Tag"
Write-Host "Web: http://localhost:30080 (NodePort) o: kubectl port-forward -n $Namespace svc/frontend 8080:80"
