# Changelog

All notable changes to `barber-saas-platform-admin-api` are recorded here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [3.0.0] - 2026-11-05

MVP 3 (corte 3): promoted from `develop` through `qa` with `git cherry-pick -x` (norm 10–11).

User stories: code-corhuila/barber-saas-docs#126, code-corhuila/barber-saas-docs#127, code-corhuila/barber-saas-docs#128, code-corhuila/barber-saas-docs#157, code-corhuila/barber-saas-docs#159.

### Added

- **plans:** let barbershop-api read a plan for the barber limit
- **internal:** serve GET /internal/v1/plans/{id} to barbershop-api
- **barbershops:** read activeBarbers from barbershop-api 1.5.0
- **payments:** add the payment and its ports
- **payments:** start a plan payment and apply the provider's result
- **persistence:** store payments in platform_admin.payment and in memory
- **wompi:** sign the checkout and check the event checksum
- **http:** serve the subscription, the payments and Wompi's events
- **app:** wire the payments and the Wompi sandbox settings
- **outbox:** add the outbox and owners ports
- **subscriptions:** suspend ended paid months and announce the end of a trial or month
- **outbox:** relay platform-admin's events to the worker
- **persistence:** store the outbox and the subscription notices
- **http-out:** read the owners of a barbershop from identity-auth
- **internal:** serve the subscription jobs and the outbox relay to the worker
- **payments:** read a payment by its reference for a checkout page
- **payments:** simulate the checkout while no Wompi key is set
- **payments:** start no payment when no checkout is configured
- **subscriptions:** name the plan in PlanExpiringSoon
- **metrics:** add the platform-wide metrics of FR-023
- **security:** suspend paid barbershops only with the subscriptions:expire scope
- **security:** relay the outbox only with outbox:relay and run the daily jobs only with jobs:run

### Fixed

- **payments:** open the simulated checkout only when PAYMENTS_SIMULATOR is true

### Documentation

- **readme:** describe the subscription and the plan payments
- **payments:** explain PAYMENTS_SIMULATOR and pass it from the environment

### Tests

- **http:** pay a plan end to end with a signed Wompi event
- **http:** cover the notices, the trial expiry event and the relay
- **http:** approve and decline a payment in the simulated checkout

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

[3.0.0]: https://github.com/code-corhuila/barber-saas-platform-admin-api/releases/tag/v3.0.0
[2.0.0]: https://github.com/code-corhuila/barber-saas-platform-admin-api/releases/tag/v2.0.0
