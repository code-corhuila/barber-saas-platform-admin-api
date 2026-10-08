# Changelog

All notable changes to `barber-saas-platform-admin-api` are recorded here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [2.0.0] - 2026-10-08

MVP 2 (corte 2): first release of this repository to `main`, promoted from `develop` through `qa`
with `git cherry-pick -x` (norm 10–11).

User stories: code-corhuila/barber-saas-docs#7, code-corhuila/barber-saas-docs#12, code-corhuila/barber-saas-docs#59.

### Added

- **http:** validate tokens, carry the correlation id and answer one error envelope
- **app:** add the composition root and the service configuration
- **plans:** model the subscription plan and its use cases
- **persistence:** keep plans in platform_admin.subscription_plan
- **http:** serve the plans of platform-admin-service
- **barbershops:** operate barbershops through barbershop-api's port
- **plans:** refuse to deactivate a plan barbershops still use
- **trials:** suspend the barbershops whose trial expired
- **http:** call barbershop-api's internal operations with this service's token
- **http:** serve the barbershops of the platform to SUPER_ADMIN
- **http:** deactivate a plan and expire trials for the worker
- **internal:** assign the plan chosen at sign-up for the workflow

### Documentation

- **readme:** point the header to Barber Saas and barber-saas-docs
- **readme:** explain the service, its modules and how to run it

### Tests

- cover the token verifier, the JSON body and the foundation over HTTP
- **http:** cover the plans over HTTP
- **http:** cover the barbershops against a fake barbershop-api

### Maintenance

- set up the three Maven modules, CI and the container

[2.0.0]: https://github.com/code-corhuila/barber-saas-platform-admin-api/releases/tag/v2.0.0
