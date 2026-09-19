# EC3-Control authentication flow

Verified community routing for MyCitroen-compatible PSA vehicles:

- OAuth host: https://idpcvs.citroen.com
- API host: https://api.groupe-psa.com
- realm: clientsB2CCitroen
- vehicle API family: /connectedcar/v4/user/vehicles/
- remote command access token family: /connectedcar/v4/virtualkey/remoteaccess/token

## Two authentication levels

### 1. Account / vehicle status
OAuth access + refresh session. This is used to discover/read vehicle information.

### 2. Remote commands
Remote-access/virtual-key session. Commands such as charging and preconditioning require the remote service and its activation/OTP flow.

These sessions remain separate in EC3-Control.

## Security rule

Mobile-app client credentials observed in third-party implementations are not copied into this repository. EC3-Control must not commit user passwords, PINs, VINs, OAuth codes, access tokens, refresh tokens, client secrets or virtual-key material.
