# MASTER ENGINEERING SPECIFICATION
# INDIA NGO MANAGEMENT & DONATION SAAS PLATFORM
# VERSION 1.0 — SELF-CONTAINED IMPLEMENTATION CONTRACT

## 0. READ THIS FIRST — YOUR ROLE AND RESPONSIBILITY

You are Claude Code, acting as the complete engineering team for a commercial software product.

Assume the roles of:

- Principal Software Architect
- Senior Flutter Developer
- Android Application Engineer
- iOS Application Engineer
- Java Spring Boot Microservices Architect
- PostgreSQL Database Architect
- Identity and Access Management Architect
- Application Security Engineer
- Financial Systems and Payment Integration Architect
- UI/UX Designer
- Quality Assurance and Test Automation Architect
- DevOps and Release Engineer
- Technical Documentation Engineer

You are responsible for understanding, designing, implementing, testing, documenting, packaging, and preparing the software for release.

**You have no prior knowledge of this project. This specification is your authoritative starting reference.**

Do not assume you can access earlier ChatGPT conversations, screenshots, specifications, or repositories unless they are explicitly present in the working directory.

Do not ask the product owner to explain functionality already described in this document.

Do not spend tokens repeatedly researching basic concepts that are already specified here.

Do not produce a prototype containing disconnected screens.

Do not substitute static mock data for working backend functionality.

Do not claim that the product is production-ready unless the relevant requirements have been implemented and verified.

### Primary objective

Build a commercially viable, secure, multi-tenant NGO Management and Donation SaaS platform for India.

Deliver:

1. A Flutter Android mobile application.
2. A Flutter iPhone mobile application.
3. A Java Spring Boot microservices backend.
4. PostgreSQL databases.
5. Secure identity and authorization.
6. NGO subscription management.
7. Donor and donation management.
8. Payment processing abstraction.
9. Custom donation receipt generation and design.
10. NGO operations management.
11. NGO internal helpdesk.
12. Platform customer-support helpdesk.
13. Notifications and reporting.
14. Automated testing.
15. Podman-based local deployment.
16. Kubernetes/OpenShift deployment configurations.
17. Android release artifacts.
18. iOS release preparation and build artifacts where tooling and credentials permit.
19. Complete technical documentation.

The product must be functional, maintainable, extensible, secure, and suitable for commercial operation after external integrations and required release approvals are completed.

---

# 1. WHAT IS THIS PRODUCT?

## 1.1 Product concept

This is a mobile-first SaaS platform that helps Indian NGOs, charitable trusts, nonprofit societies, and Section 8 companies digitize their daily operations.

Today, many smaller NGOs manage their work through:

- Paper donation registers.
- Manual receipt books.
- Excel spreadsheets.
- WhatsApp messages.
- Personal phone contacts.
- Manual donor follow-ups.
- Separate volunteer registers.
- Paper expense records.
- Unstructured beneficiary records.
- Manual subscription and payment tracking.

Our product brings these activities into one mobile application.

An NGO can register, subscribe to an affordable plan, invite staff, manage donors, collect donations, issue receipts, organize campaigns, manage volunteers, track projects, maintain financial records, and respond to support requests.

Donors use the same mobile application to discover NGOs, support causes, make donations, access receipts, follow organizations, and review their donation history.

The software provider earns revenue through NGO subscriptions.

**The platform does not charge a percentage commission on donations.**

Donation funds must flow through the NGO's own approved payment-provider merchant account to its registered settlement account.

## 1.2 Target customers

Primary customers:

- Small charitable trusts.
- Local NGOs.
- Educational charities.
- Healthcare charities.
- Community welfare organizations.
- Rural development NGOs.
- Social welfare societies.
- Section 8 nonprofit companies.
- Medium-sized NGOs.
- Larger organizations managing multiple teams and projects.

Initial geographic focus:

- Maharashtra.
- Mumbai Metropolitan Region.
- Thane.
- Kalyan-Dombivli.
- Navi Mumbai.
- Pune.
- Palghar.
- Dahanu.

The architecture must support expansion across India.

## 1.3 User personas

### NGO Primary Administrator

Registers the organization, manages its subscription, staff, permissions, donation configuration, and overall operations.

### NGO Secondary Administrator

Provides administrative continuity, backup access, and approvals for sensitive actions.

### NGO Staff

Performs operational work according to assigned permissions.

### NGO Finance Staff

Manages financial records, reconciliations, receipts, and expenses.

### NGO Campaign Manager

Creates and manages fundraising campaigns.

### NGO Volunteer Coordinator

Manages volunteers, assignments, and attendance.

### Volunteer

Views permitted assignments and records participation.

### Donor

Discovers NGOs, donates, views receipts, and manages donation history.

### Guest Donor

Makes an eligible donation without creating a permanent account.

### Platform Administrator

Manages NGO onboarding, verification, subscriptions, support, risk review, and platform operations.

### Platform Support Executive

Handles SaaS-related support tickets with limited, audited access.

---

# 2. PRODUCT PRINCIPLES

Follow these principles throughout development.

1. Mobile-first design.
2. One Flutter codebase for Android and iOS.
3. Separate NGO and donor experiences.
4. Secure multi-tenancy.
5. Backend-enforced permissions.
6. Backend-enforced subscription entitlements.
7. Financial transaction integrity.
8. Reliable asynchronous processing.
9. Recoverable failures.
10. Minimal collection of personal data.
11. Configurable business rules.
12. Maintainable microservices.
13. Open-source-first dependencies.
14. No unnecessary proprietary lock-in.
15. Automated testing from the beginning.
16. No silent data loss.
17. No fabricated production payment success.
18. No cross-NGO data exposure.
19. No misleading claims of legal compliance.
20. No unverified production-readiness claims.

---

# 3. TECHNOLOGY STACK AND OPEN-SOURCE POLICY

## 3.1 Licensing requirements

Prefer libraries under:

- MIT
- Apache License 2.0
- BSD-2-Clause
- BSD-3-Clause
- PostgreSQL License

The product owner prefers MIT-licensed software wherever practical.

However, the core requested stack contains components under other permissive licenses.

Do not replace Spring Boot or PostgreSQL merely to satisfy an MIT-only interpretation.

Avoid GPL/AGPL/SSPL and other reciprocal or source-availability licenses unless specifically reviewed and approved.

For every dependency:

- Record its version.
- Record its license.
- Record its purpose.
- Check maintenance status.
- Check known vulnerabilities.
- Confirm commercial-use suitability.
- Document redistribution obligations.

Create:

`THIRD_PARTY_LICENSES.md`

`DEPENDENCY_INVENTORY.md`

Generate an SBOM.

Do not claim a dependency is MIT-licensed without verification.

## 3.2 Flutter mobile stack

Use:

- Flutter stable.
- Dart stable compatible with Flutter.
- Material 3.
- Riverpod.
- GoRouter.
- Dio.
- Freezed where useful.
- JSON serialization.
- Flutter Secure Storage.
- Drift/SQLite for appropriate local persistence.
- Flutter localization.
- Native platform integrations where required.

Select stable, maintained versions compatible with the chosen Flutter release.

Avoid introducing multiple state-management frameworks.

## 3.3 Backend stack

Use:

- Java 21 LTS.
- Stable Spring Boot release compatible with Java 21.
- Spring Web.
- Spring Security.
- Spring Data JPA.
- Hibernate ORM.
- Hibernate Validator.
- PostgreSQL JDBC.
- Flyway.
- Spring Actuator.
- Micrometer.
- OpenTelemetry.
- Spring Boot Test.
- JUnit 5.
- Mockito.
- Testcontainers.
- REST Assured.

Use Maven as the default build system.

## 3.4 Database

Use PostgreSQL.

Prefer separate PostgreSQL databases per microservice, hosted initially on a shared PostgreSQL server for local development.

Microservices must not directly query another service's private tables.

Use service APIs and events for cross-service communication.

Use:

- UUID primary keys.
- Foreign keys.
- Unique constraints.
- Check constraints.
- Indexes.
- Transactions.
- Optimistic locking where appropriate.
- Flyway migrations.
- UTC timestamps.
- Decimal monetary types.
- Immutable financial history.

## 3.5 Authentication

Use Keycloak as the preferred identity provider.

Use:

- OpenID Connect.
- OAuth 2.0.
- Authorization Code Flow with PKCE.
- Spring Security resource-server validation.
- Role-based and permission-based authorization.

Keycloak is an identity provider, not the authoritative store for every NGO business relationship.

Keep NGO membership, staff assignments, business permissions, and subscription entitlements in application databases.

## 3.6 Messaging

Use RabbitMQ initially.

Reasons:

- Asynchronous processing.
- Reliable message delivery.
- Notification queues.
- Receipt generation jobs.
- Payment event processing.
- Retry handling.
- Dead-letter queues.

Use a transactional outbox pattern where durable business events must be published.

Do not assume exactly-once message delivery.

Consumers must be idempotent.

## 3.7 Object storage

Use MinIO or another approved S3-compatible implementation for local development, subject to license review.

If a selected storage implementation does not meet licensing requirements, choose a compatible alternative or use a documented storage adapter.

Store:

- NGO registration documents.
- Campaign images.
- Receipt templates.
- Generated PDFs.
- Financial attachments.
- Helpdesk attachments.
- Export files.

Use private object storage by default.

## 3.8 PDF generation

Use Apache PDFBox or another suitable permissively licensed Java PDF library.

Generate authoritative receipts on the backend.

Do not use Flutter screenshots as the official receipt-generation mechanism.

## 3.9 Deployment

Use:

- Podman.
- OCI Containerfiles.
- Kubernetes manifests.
- OpenShift-compatible manifests.
- GitHub Actions.
- OpenTelemetry.
- Prometheus-compatible metrics.

Use OpenShift-compatible security settings.

Do not require privileged containers.

---

# 4. MICROSERVICES ARCHITECTURE

The product owner explicitly requires Spring Boot microservices.

Build independently deployable services with clear domain ownership.

Avoid creating one service per screen.

## 4.1 Required microservices

### Service 1 — API Gateway

Responsibilities:

- External API routing.
- Authentication integration.
- Request correlation.
- Rate limiting.
- API version routing.
- Security headers.
- Request-size limits.

Do not place business logic in the gateway.

### Service 2 — Identity and Membership Service

Responsibilities:

- User profiles.
- Keycloak identity mapping.
- NGO memberships.
- Role assignments.
- Staff invitations.
- Administrator lifecycle.
- Permission evaluation.
- Account status.
- Audit events.

### Service 3 — NGO Registry Service

Responsibilities:

- NGO registration.
- NGO profiles.
- Darpan details.
- Legal documents.
- NGO verification.
- Organization contacts.
- Public NGO profiles.
- Donation eligibility.
- NGO risk-review workflow.

### Service 4 — Subscription and Billing Service

Responsibilities:

- Subscription plans.
- Plan entitlements.
- Trial lifecycle.
- Quarterly subscriptions.
- Annual subscriptions.
- Upgrades.
- Downgrades.
- Enterprise staff blocks.
- Renewal.
- Expiry.
- Suspension.
- Reactivation.
- Subscription invoices.
- Subscription payment records.

### Service 5 — Donor Service

Responsibilities:

- Donor profiles.
- Donor-to-NGO relationships.
- Guest donor references.
- Donor preferences.
- Donor identity linking.
- Donor privacy requests.
- Donor search.
- Donor segmentation.

### Service 6 — Donation and Payment Service

Responsibilities:

- Donation orders.
- Payment-provider abstraction.
- Mock payment processing.
- Payment webhooks.
- Refunds.
- Payment event history.
- Settlement records.
- Reconciliation.
- Donation ledger.
- Financial exceptions.

### Service 7 — Receipt Service

Responsibilities:

- Standard receipts.
- Basic custom receipt designer.
- Advanced custom receipt designer.
- Template storage.
- Template versioning.
- Receipt numbering.
- PDF generation.
- Receipt delivery jobs.
- Receipt corrections.
- Receipt cancellation/reissue.

### Service 8 — Campaign and Project Service

Responsibilities:

- Fundraising campaigns.
- Campaign goals.
- Project management.
- Project budgets.
- Milestones.
- Project assignments.
- Campaign publishing.
- Project reporting.

### Service 9 — NGO Operations Service

Responsibilities:

- Volunteers.
- Volunteer attendance.
- Beneficiaries.
- Assistance distribution.
- NGO operational activities.
- Basic expense records.
- Operational reporting inputs.

### Service 10 — Helpdesk Service

Responsibilities:

- NGO internal support tickets.
- Platform support tickets.
- Ticket categories.
- Ticket assignment.
- Ticket conversations.
- Attachments.
- SLA tracking.
- Escalation.
- Resolution.
- Feedback.
- Ticket analytics.

### Service 11 — Notification Service

Responsibilities:

- Email.
- In-app notifications.
- Push notifications.
- Notification templates.
- Delivery retries.
- Communication preferences.
- Delivery status.
- Subscription reminders.
- Helpdesk alerts.

### Service 12 — Reporting Service

Responsibilities:

- Reporting projections.
- NGO dashboards.
- Aggregated statistics.
- Financial reports.
- Campaign reports.
- Staff activity reports.
- Export generation.

Use event-driven projections where appropriate.

Do not perform unauthorized direct cross-service database joins.

## 4.2 Platform administration

Implement platform administration as secured APIs owned by the relevant services and exposed through the gateway.

A separate platform-administration service is optional.

Do not duplicate subscription, NGO verification, or payment business logic in an administrative service.

## 4.3 Microservice communication

Use synchronous REST for operations requiring immediate responses.

Use asynchronous events for:

- NGO registration.
- NGO verification completion.
- Staff invitation.
- Subscription activation.
- Subscription expiry.
- Payment confirmation.
- Donation completion.
- Receipt generation.
- Notification dispatch.
- Ticket creation.
- Ticket escalation.

Every event must include:

- Event ID.
- Event type.
- Event version.
- Timestamp.
- Correlation ID.
- Tenant ID where applicable.
- Resource ID.
- Minimal required payload.

Avoid placing sensitive personal data in event payloads unnecessarily.

## 4.4 Distributed transactions

Do not use distributed database transactions across microservices.

Use:

- Transactional outbox.
- Idempotent consumers.
- Compensating actions.
- Reconciliation.
- Retry policies.
- Dead-letter queues.

Document failure handling for every cross-service workflow.

---

# 5. REPOSITORY STRUCTURE

Create a monorepo with independently buildable services.

Suggested structure:

ngo-platform/
  apps/
    mobile/
      lib/
        app/
        core/
        design_system/
        features/
        localization/
        routing/
      test/
      integration_test/
      android/
      ios/

  services/
    api-gateway/
    identity-service/
    ngo-registry-service/
    subscription-service/
    donor-service/
    donation-payment-service/
    receipt-service/
    campaign-project-service/
    ngo-operations-service/
    helpdesk-service/
    notification-service/
    reporting-service/

  shared/
    api-contracts/
    event-contracts/
    test-utilities/

  infrastructure/
    podman/
    kubernetes/
    openshift/
    observability/
    database/
    scripts/

  docs/
    architecture/
    business/
    api/
    database/
    testing/
    security/
    release/

Avoid a large shared Java library containing all domain entities.

Shared libraries must remain limited to stable technical contracts and utilities.

---

# 6. COMPLETE BUSINESS MODEL

## 6.1 Subscription plans

The following commercial rules are confirmed.

| Plan | Monthly equivalent | Quarterly charge | Included staff |
|---|---:|---:|---:|
| Community | ₹199 | ₹597 | 1 |
| Growth | ₹499 | ₹1,497 | 4 |
| Professional | ₹1,199 | ₹3,597 | 15 |
| Enterprise | ₹1,699 | ₹5,097 | 30 |

Prices include GST.

All plans include a 30-day free trial.

Minimum paid subscription term: three months.

Annual billing is supported.

Initial proposed annual discount: 10%, configurable.

No permanently free NGO subscription.

Donor accounts remain free.

The platform charges 0% donation commission.

## 6.2 Administrator accounts

Initial implementation assumption:

- Two administrator accounts per NGO.
- Administrators do not consume operational staff allowances.

Make this policy configurable.

Do not hard-code administrator counting into database relationships.

## 6.3 Additional staff

Only Enterprise can purchase additional staff.

Rules:

- 30 staff included.
- Additional staff purchased in blocks of 10.
- ₹99 per additional staff member per month.
- ₹990 per block per month.
- Quarterly charge per block: ₹2,970.
- Multiple blocks supported.
- Mid-cycle purchases prorated.
- Additional blocks renew with the main subscription.
- Block removal takes effect at renewal.
- Cannot reduce capacity below occupied staff seats without resolving the conflict.

Community, Growth and Professional cannot purchase additional staff.

They must upgrade to a higher plan.

## 6.4 Receipt entitlement

Community:

- Standard receipts.

Growth:

- Standard receipts.

Professional:

- Standard receipts.
- Basic Custom Receipt Designer.

Enterprise:

- Standard receipts.
- Basic Custom Receipt Designer.
- Advanced Custom Receipt Designer.

## 6.5 Other plan features

The following feature limits are provisional and must remain configurable.

| Capability | Community | Growth | Professional | Enterprise |
|---|---|---|---|---|
| Donor relationships | Unlimited | Unlimited | Unlimited | Unlimited |
| Basic donation management | Yes | Yes | Yes | Yes |
| Standard receipts | Yes | Yes | Yes | Yes |
| Active campaigns | 2 | 10 | Unlimited | Unlimited |
| Active projects | 2 | 10 | Unlimited | Unlimited |
| Volunteer management | Basic | Standard | Advanced | Advanced |
| Financial reports | Basic | Standard | Advanced | Advanced |
| Donor segmentation | Basic | Standard | Advanced | Advanced |
| Basic custom receipt | No | No | Yes | Yes |
| Advanced custom receipt | No | No | No | Yes |
| Additional staff blocks | No | No | No | Yes |
| Custom reporting | No | No | Limited | Advanced |
| API integrations | No | No | Optional add-on | Optional add-on |
| Platform helpdesk | Yes | Yes | Yes | Yes |
| Priority platform support | No | No | Yes | Yes |

Do not make security, historical financial records, data export, or legally necessary receipt access premium-only features.

## 6.6 Entitlement implementation

Create database-driven entitlements.

Examples:

STAFF_MAX
CAMPAIGN_ACTIVE_MAX
PROJECT_ACTIVE_MAX
RECEIPT_BASIC_DESIGNER
RECEIPT_ADVANCED_DESIGNER
REPORTING_LEVEL
VOLUNTEER_FEATURE_LEVEL
CUSTOM_INTEGRATION_ALLOWED
PRIORITY_SUPPORT

Use server-side authorization and entitlement checks.

Flutter receives effective entitlements from the backend.

Never rely on a client-supplied subscription plan.

---

# 7. SUBSCRIPTION LIFECYCLE

## 7.1 States

TRIAL
ACTIVE
GRACE
SUSPENDED
CANCELLED
EXPIRED
ARCHIVED

Maintain separate:

- NGO account status.
- NGO verification status.
- Donation eligibility status.
- Subscription status.

These statuses must not be conflated.

## 7.2 Trial

- 30 days.
- One eligible trial per NGO according to anti-abuse rules.
- Trial begins when the approved workspace becomes usable.
- Trial countdown.
- Trial expiry reminders.
- Trial-to-paid conversion.
- No automatic paid conversion without authorization.

## 7.3 Quarterly billing

Charge three months upfront.

Display monthly equivalent and actual quarterly payment.

## 7.4 Annual billing

Support annual subscription with configurable discount.

Preserve actual contracted prices for later proration.

## 7.5 Upgrades

Allow upgrades at any time.

Examples:

- Community to Growth.
- Community to Professional.
- Community to Enterprise.
- Growth to Professional.
- Growth to Enterprise.
- Professional to Enterprise.

Calculate prorated difference for the remaining billing period.

Preserve the existing renewal date.

Activate new entitlements only after verified payment.

Use BigDecimal for money.

Do not use floating-point currency calculations.

## 7.6 Downgrades

Schedule downgrades for the next renewal.

Before confirmation:

- Identify excess staff.
- Identify unsupported receipt templates.
- Identify feature restrictions.
- Identify campaign/project capacity conflicts.
- Show the impact.
- Require administrator confirmation.

Never delete existing data simply because a plan changes.

## 7.7 Expiry

Send reminders:

- 15 days before expiry.
- 7 days before expiry.
- 1 day before expiry.
- On expiry.
- During grace period.
- On suspension.

Grace period: 15 days.

After grace:

- Subscription becomes suspended.
- NGO administrators can log in.
- Historical data remains accessible.
- Existing receipts remain downloadable.
- Permitted exports remain available.
- New paid operations are blocked.
- New donation checkout is paused.
- Essential payment reconciliation continues.

## 7.8 Reactivation

If an NGO returns after five months:

- Do not require new registration.
- Preserve existing records.
- Display subscription renewal.
- Charge only for the new subscription period.
- Reactivate after verified payment.
- Restore entitlements according to the selected plan.

Do not automatically restore donation eligibility if a separate compliance suspension remains active.

## 7.9 Edge cases

Implement and test:

- Multiple upgrades in one quarter.
- Upgrade during annual billing.
- Upgrade payment failure.
- Delayed payment success.
- Duplicate payment callback.
- Concurrent upgrades.
- Add-on purchase during upgrade.
- Add-on removal at renewal.
- Downgrade with excess staff.
- Cancellation during paid term.
- Renewal after expiry.
- Renewal after suspension.
- Refund after upgrade.
- Subscription dispute.
- Incorrect payment amount.
- Expired payment quotation.
- Concurrent renewal requests.
- Duplicate invoices.
- Stale subscription version.

---

# 8. MOBILE UI/UX DESIGN

## 8.1 Visual identity

Use the previously discussed NGO mobile application design as the intended visual reference.

If the image is available in the repository, inspect it.

If it is not available, use the following design description.

The application should look:

- Modern.
- Trustworthy.
- Warm.
- Human-centered.
- Professional.
- Welcoming to social workers.
- Attractive to donors.
- Easy for nontechnical users.

## 8.2 Colors

Primary forest green: #087A4B

Dark green: #064E3B

Warm background: #FAFBF7

White surfaces: #FFFFFF

Primary text: #14213D

Growth accent: #1976D2

Professional accent: #7437E8

Enterprise accent: #E97716

Error: #C62828

Warning: #F59E0B

Use centralized design tokens.

## 8.3 Visual components

Use:

- Rounded cards.
- Soft shadows.
- Clear headings.
- Friendly icons.
- Human-centered imagery.
- Clear primary actions.
- Comfortable spacing.
- Progress indicators.
- Status badges.
- Informational tooltips.
- Bottom navigation.
- Accessible forms.
- Consistent dialogs.
- Clear success and failure messages.

Avoid overcrowded dashboards.

Avoid desktop-style tables on small mobile screens.

Use responsive mobile lists, cards, bottom sheets, and drill-down pages.

## 8.4 Navigation

NGO:

Home
Donors
Campaigns
Reports
More

Donor:

Discover
Donations
Following
Profile

Role-specific navigation must be enforced.

## 8.5 Languages

Support:

- English.
- Marathi.
- Hindi.

Use localization resources.

Do not hard-code visible strings.

Provide language selection during onboarding and in settings.

## 8.6 Accessibility

Support:

- VoiceOver.
- TalkBack.
- Dynamic text scaling.
- High contrast.
- Semantic labels.
- Accessible touch targets.
- Keyboard navigation where applicable.
- Screen-reader announcements.
- Reduced-motion preferences.

---

# 9. COMPLETE SCREEN INVENTORY

Create every required screen.

For every screen, define:

- Screen ID.
- User role.
- Purpose.
- Navigation.
- Fields.
- Field validation.
- API.
- Permissions.
- Entitlements.
- Loading state.
- Empty state.
- Error state.
- Success state.
- Offline behavior.
- Automated tests.

## 9.1 Application startup

APP-001 Splash
APP-002 Welcome
APP-003 Language selection
APP-004 User type selection
APP-005 NGO introduction
APP-006 Donor introduction
APP-007 Terms acceptance
APP-008 Privacy notice
APP-009 Permissions explanation
APP-010 Maintenance mode
APP-011 Update required
APP-012 No internet
APP-013 Unexpected error

## 9.2 Authentication

AUTH-001 NGO login
AUTH-002 Donor login
AUTH-003 Registration
AUTH-004 Email verification
AUTH-005 OTP verification
AUTH-006 Forgot password
AUTH-007 Reset password
AUTH-008 MFA enrollment
AUTH-009 MFA verification
AUTH-010 Session expired
AUTH-011 Logout
AUTH-012 Device sessions
AUTH-013 Account recovery
AUTH-014 Social login failure
AUTH-015 Identity linking
AUTH-016 Account deletion request

## 9.3 NGO registration

NGO-001 Registration wizard
NGO-002 Organization details
NGO-003 Registration details
NGO-004 Darpan information
NGO-005 PAN/tax information
NGO-006 Address
NGO-007 Contact details
NGO-008 Primary administrator
NGO-009 Secondary administrator
NGO-010 Documents
NGO-011 Authorized signatory
NGO-012 Bank/payment configuration
NGO-013 Review
NGO-014 Submit
NGO-015 Verification pending
NGO-016 Additional information required
NGO-017 Verification rejected
NGO-018 Resubmit
NGO-019 Verification approved
NGO-020 NGO profile
NGO-021 Edit profile
NGO-022 Branding
NGO-023 Public profile preview
NGO-024 Verification history
NGO-025 Document management
NGO-026 Document expiry
NGO-027 Legal information change
NGO-028 Bank information change
NGO-029 Suspension
NGO-030 Account closure

## 9.4 NGO dashboard

DASH-001 Home
DASH-002 Donations summary
DASH-003 Donors summary
DASH-004 Campaign summary
DASH-005 Projects summary
DASH-006 Volunteers summary
DASH-007 Finance summary
DASH-008 Recent activities
DASH-009 Pending approvals
DASH-010 Subscription summary
DASH-011 Notifications
DASH-012 Quick actions
DASH-013 Activity details
DASH-014 Dashboard filters

## 9.5 Subscription screens

SUB-001 Overview
SUB-002 All plans
SUB-003 Compare plans
SUB-004 Community details
SUB-005 Growth details
SUB-006 Professional details
SUB-007 Enterprise details
SUB-008 Staff calculator
SUB-009 Trial activation
SUB-010 Trial countdown
SUB-011 Trial expiry
SUB-012 Billing cycle
SUB-013 Checkout
SUB-014 Payment pending
SUB-015 Payment success
SUB-016 Payment failure
SUB-017 Upgrade
SUB-018 Upgrade breakdown
SUB-019 Upgrade confirmation
SUB-020 Downgrade
SUB-021 Downgrade impact
SUB-022 Downgrade confirmation
SUB-023 Add staff blocks
SUB-024 Remove staff blocks
SUB-025 Add-on proration
SUB-026 Renewal
SUB-027 Renewal confirmation
SUB-028 Billing history
SUB-029 Invoice details
SUB-030 Invoice download
SUB-031 Billing contact
SUB-032 Tax information
SUB-033 Auto-renewal
SUB-034 Cancellation request
SUB-035 Cancellation confirmation
SUB-036 Expired
SUB-037 Grace warning
SUB-038 Read-only suspension
SUB-039 Reactivation
SUB-040 Subscription history
SUB-041 Credit history
SUB-042 Refund request
SUB-043 Refund status
SUB-044 Payment dispute
SUB-045 Subscription help

## 9.6 Staff and administrators

STAFF-001 Directory
STAFF-002 Details
STAFF-003 Add
STAFF-004 Invite
STAFF-005 Pending invitations
STAFF-006 Accept invitation
STAFF-007 Edit
STAFF-008 Role assignment
STAFF-009 Permissions
STAFF-010 Activate
STAFF-011 Deactivate
STAFF-012 Remove
STAFF-013 Activity history
STAFF-014 Limit reached
STAFF-015 Administrator directory
STAFF-016 Secondary administrator
STAFF-017 Transfer primary administrator
STAFF-018 Approval request
STAFF-019 Administrator recovery
STAFF-020 Invitation expired
STAFF-021 Access denied
STAFF-022 Capacity
STAFF-023 Import
STAFF-024 Import results
STAFF-025 Export

## 9.7 Donor management

DONOR-001 Directory
DONOR-002 Add donor
DONOR-003 Edit donor
DONOR-004 Donor details
DONOR-005 Donation history
DONOR-006 Search
DONOR-007 Filters
DONOR-008 Segmentation
DONOR-009 Preferences
DONOR-010 Import
DONOR-011 Import preview
DONOR-012 Import results
DONOR-013 Duplicate review
DONOR-014 Merge
DONOR-015 Export
DONOR-016 Privacy request
DONOR-017 Activity timeline
DONOR-018 Organization donor

## 9.8 Donor application

DAPP-001 Donor home
DAPP-002 Discover NGOs
DAPP-003 NGO search
DAPP-004 Filters
DAPP-005 NGO profile
DAPP-006 NGO verification information
DAPP-007 Campaign listing
DAPP-008 Campaign details
DAPP-009 Donation amount
DAPP-010 Donor information
DAPP-011 Guest donation
DAPP-012 Payment method
DAPP-013 Payment processing
DAPP-014 Payment pending
DAPP-015 Payment success
DAPP-016 Payment failure
DAPP-017 Donation history
DAPP-018 Donation details
DAPP-019 Receipt preview
DAPP-020 Receipt download
DAPP-021 Receipt resend
DAPP-022 Followed NGOs
DAPP-023 Saved campaigns
DAPP-024 Profile
DAPP-025 Edit profile
DAPP-026 Communication preferences
DAPP-027 Privacy settings
DAPP-028 Data access request
DAPP-029 Data correction request
DAPP-030 Account closure
DAPP-031 Helpdesk
DAPP-032 Ticket history
DAPP-033 Ticket details
DAPP-034 Notifications

## 9.9 Donation operations

PAY-001 Donation dashboard
PAY-002 Donation list
PAY-003 Donation detail
PAY-004 Offline donation
PAY-005 Offline payment evidence
PAY-006 Allocation
PAY-007 Payment status
PAY-008 Payment event history
PAY-009 Reconciliation
PAY-010 Settlement batches
PAY-011 Settlement detail
PAY-012 Unmatched transactions
PAY-013 Reconciliation exceptions
PAY-014 Refund request
PAY-015 Refund approval
PAY-016 Refund status
PAY-017 Donation correction
PAY-018 Receipt status
PAY-019 Export
PAY-020 Audit history

## 9.10 Receipt management

REC-001 Receipt directory
REC-002 Receipt details
REC-003 Standard preview
REC-004 Download
REC-005 Resend
REC-006 Correction
REC-007 Cancel/reissue
REC-008 Template directory
REC-009 Background upload
REC-010 Basic designer
REC-011 Advanced designer
REC-012 Field properties
REC-013 Template preview
REC-014 Validation results
REC-015 Save draft
REC-016 Publish
REC-017 Approval
REC-018 Version history
REC-019 Rollback
REC-020 Archive
REC-021 Generation failure
REC-022 Retry history

## 9.11 Campaigns

CAMP-001 Directory
CAMP-002 Create
CAMP-003 Details
CAMP-004 Edit
CAMP-005 Preview
CAMP-006 Publish
CAMP-007 Approval
CAMP-008 Donation history
CAMP-009 Analytics
CAMP-010 Share
CAMP-011 Close
CAMP-012 Archive

## 9.12 Projects

PROJ-001 Directory
PROJ-002 Create
PROJ-003 Edit
PROJ-004 Details
PROJ-005 Milestones
PROJ-006 Assignments
PROJ-007 Budget
PROJ-008 Expenses
PROJ-009 Documents
PROJ-010 Activity
PROJ-011 Progress
PROJ-012 Reports
PROJ-013 Close

## 9.13 Beneficiaries

BEN-001 Directory
BEN-002 Register
BEN-003 Details
BEN-004 Edit
BEN-005 Verification
BEN-006 Project assignment
BEN-007 Assistance distribution
BEN-008 Assistance history
BEN-009 Documents
BEN-010 Search
BEN-011 Reports
BEN-012 Privacy controls

## 9.14 Volunteers

VOL-001 Directory
VOL-002 Registration
VOL-003 Details
VOL-004 Assignments
VOL-005 Availability
VOL-006 Attendance
VOL-007 Activities
VOL-008 Hours
VOL-009 Reports
VOL-010 Deactivation

## 9.15 Finance

FIN-001 Dashboard
FIN-002 Income
FIN-003 Expenses
FIN-004 Add expense
FIN-005 Expense detail
FIN-006 Approval
FIN-007 Categories
FIN-008 Budgets
FIN-009 Project finance
FIN-010 Attachments
FIN-011 Exports
FIN-012 Reconciliation exceptions
FIN-013 Adjustments
FIN-014 Audit

## 9.16 Reports

RPT-001 Dashboard
RPT-002 Donations
RPT-003 Donors
RPT-004 Campaigns
RPT-005 Projects
RPT-006 Volunteers
RPT-007 Beneficiaries
RPT-008 Expenses
RPT-009 Financial summary
RPT-010 Subscription usage
RPT-011 Staff activity
RPT-012 Custom reports
RPT-013 Export history
RPT-014 Generation status

## 9.17 Notifications

NOTIF-001 Inbox
NOTIF-002 Details
NOTIF-003 Preferences
NOTIF-004 Email settings
NOTIF-005 Push settings
NOTIF-006 Communication history
NOTIF-007 Delivery failure
NOTIF-008 Template preview

## 9.18 Settings

SET-001 NGO settings
SET-002 Profile
SET-003 Security
SET-004 Language
SET-005 Appearance
SET-006 Password/security
SET-007 Connected identities
SET-008 Sessions
SET-009 Documents
SET-010 Receipts
SET-011 Subscription
SET-012 Payment
SET-013 Privacy
SET-014 Data export
SET-015 Account closure
SET-016 About
SET-017 Terms and policies

## 9.19 Platform administration

ADMIN-001 Dashboard
ADMIN-002 NGO verification queue
ADMIN-003 Verification details
ADMIN-004 Approve/reject
ADMIN-005 NGO suspension
ADMIN-006 Plan catalog
ADMIN-007 Entitlements
ADMIN-008 Subscription exceptions
ADMIN-009 Payment exceptions
ADMIN-010 Refund review
ADMIN-011 Support queue
ADMIN-012 Ticket details
ADMIN-013 Ticket assignment
ADMIN-014 SLA configuration
ADMIN-015 Audit review
ADMIN-016 Notification templates
ADMIN-017 Feature flags
ADMIN-018 Configuration
ADMIN-019 System health

## 9.20 Helpdesk

HELP-001 Dashboard
HELP-002 My tickets
HELP-003 Authorized ticket queue
HELP-004 Create ticket
HELP-005 Category selection
HELP-006 Ticket form
HELP-007 Attachment upload
HELP-008 Submission confirmation
HELP-009 Ticket detail
HELP-010 Conversation
HELP-011 Public reply
HELP-012 Internal note
HELP-013 Assign
HELP-014 Reassign
HELP-015 Priority
HELP-016 Status
HELP-017 SLA countdown
HELP-018 Escalation
HELP-019 Request information
HELP-020 Resolution
HELP-021 Close
HELP-022 Reopen
HELP-023 Feedback
HELP-024 Search
HELP-025 Filters
HELP-026 History
HELP-027 Audit
HELP-028 Reports
HELP-029 Configuration
HELP-030 SLA settings
HELP-031 Categories
HELP-032 Knowledge base
HELP-033 FAQ
HELP-034 Contact support
HELP-035 Platform support request

These are functional screen requirements, not a requirement to create a separate Flutter widget for every status or action.

Use dialogs, bottom sheets, reusable components, and shared pages where appropriate.

However, every listed user journey must be accessible and functional.

---

# 10. NGO REGISTRATION AND VERIFICATION

Implement a complete onboarding workflow.

Collect:

- NGO legal name.
- Display name.
- Registration type.
- Registration number.
- Registration date.
- Darpan ID where available.
- PAN where applicable.
- Registered address.
- State.
- District.
- City/taluka.
- PIN code.
- Official email.
- Official phone.
- Primary administrator.
- Secondary administrator.
- Authorized signatory.
- Organization description.
- Areas of activity.
- Registration documents.
- Tax eligibility documents where applicable.
- Payment-provider onboarding information.

Separate:

1. NGO registration status.
2. NGO verification status.
3. Public profile verification.
4. Donation collection eligibility.
5. Payment-provider onboarding status.

A Darpan ID is not proof of legitimacy.

Do not assume an official Darpan API is available.

Support manual review.

Implement:

- Duplicate NGO detection.
- Duplicate registration number detection.
- Duplicate PAN review.
- Document validation.
- Verification approval.
- Rejection.
- Resubmission.
- Risk flags.
- Review history.
- Periodic reverification.
- Sensitive-change approval.

Do not expose private verification records to donors.

---

# 11. STAFF AND ADMINISTRATOR MANAGEMENT

## 11.1 Staff lifecycle

INVITED
PENDING_ACCEPTANCE
ACTIVE
SUSPENDED
DEACTIVATED
REMOVED

Support:

- Invitation.
- Acceptance.
- Expiration.
- Resend.
- Cancellation.
- Role assignment.
- Permission changes.
- Deactivation.
- Reactivation.
- Removal.
- Activity audit.

## 11.2 Administrator lifecycle

Support:

- Primary administrator.
- Secondary administrator.
- Administrator invitation.
- Administrator transfer.
- Administrator replacement.
- Administrator recovery.
- Critical-change approval.

Never allow removal of the last authorized administrator.

Require step-up authentication for sensitive changes.

## 11.3 Staff capacity

Enforce staff limits transactionally.

Prevent two concurrent invitations from exceeding capacity.

Define whether pending invitations reserve seats.

Initial recommendation: accepted invitations and active staff consume seats; pending invitations reserve seats until expiration or cancellation.

Make this policy configurable.

---

# 12. DONOR MANAGEMENT

Implement:

- Individual donors.
- Organizational donors.
- Guest donors.
- Donor profiles.
- Donor search.
- Donor segmentation.
- Donation history.
- Donor preferences.
- Donor communications.
- Donor exports.
- Duplicate detection.
- Identity linking.
- Privacy requests.

A donor may support multiple NGOs.

An NGO must not access the donor's private activity with another NGO.

Support guest checkout where permitted.

Do not force account registration before every donation.

Allow secure receipt retrieval through verified email or another approved mechanism.

---

# 13. DONATIONS AND PAYMENT PROCESSING

## 13.1 Payment ownership

The NGO owns its payment-provider merchant account.

The donor pays through the approved provider.

The provider settles funds to the NGO's registered settlement account.

The SaaS does not collect donation funds into its own operating account.

Subscription fees are separate payments to the SaaS provider.

## 13.2 Payment abstraction

Create:

PaymentProvider

Methods:

createPaymentOrder
getPaymentStatus
verifyWebhook
getSettlement
getRefundStatus
initiateRefund

Implement a deterministic MockPaymentProvider.

Mock scenarios:

- Success.
- Failure.
- Pending.
- Cancellation.
- Timeout.
- Delayed success.
- Duplicate webhook.
- Invalid signature.
- Refund success.
- Refund failure.
- Partial refund.
- Settlement mismatch.

Never enable development mock controls in production.

## 13.3 Payment states

CREATED
PENDING
AUTHORIZED
PAID
FAILED
CANCELLED
REFUNDED
PARTIALLY_REFUNDED
DISPUTED

Implement explicit transition rules.

## 13.4 Payment recovery

Handle:

- App closes during payment.
- Internet disconnects.
- Provider timeout.
- Payment succeeds but callback fails.
- Webhook arrives late.
- Webhook arrives twice.
- Webhook arrives out of order.
- Database unavailable.
- Receipt generation fails.
- Notification delivery fails.
- Donor retries.
- Refund after receipt generation.

Use durable events, idempotency, retries, and reconciliation.

Do not create duplicate donations or receipts.

---

# 14. PAYMENT LEDGER AND ACCOUNTING

Maintain separate financial domains for:

1. NGO donations.
2. SaaS subscriptions.

Track:

- Gross donation.
- Gateway fee.
- Tax on gateway fee.
- Net settlement.
- Settlement batch.
- Bank reference.
- Refunds.
- Disputes.
- Adjustments.

Use a balanced journal model where accounting entries are posted.

Suggested tables:

ledger_accounts
journal_entries
journal_lines
payment_events
settlement_batches
reconciliation_runs
reconciliation_exceptions

Never silently edit posted financial entries.

Use reversals or adjustments.

Support:

- Provider reconciliation.
- Settlement reconciliation.
- Bank statement matching.
- Partial settlement.
- Delayed settlement.
- Missing settlement.
- Duplicate settlement.
- Refund reconciliation.
- Fee reconciliation.

Document the distinction between the platform's operational donation ledger and the NGO's statutory accounting records.

Do not claim to replace certified accounting software.

---

# 15. CUSTOM RECEIPT CREATOR

This is a major differentiating feature.

## 15.1 Standard receipts

All plans.

Include:

- NGO name.
- NGO logo.
- Receipt number.
- Donor name.
- Donation amount.
- Donation date.
- Payment reference.
- Donation purpose.
- Authorized signature where configured.
- PDF download.
- Receipt email.
- Historical access.

## 15.2 Basic Custom Receipt Designer

Professional and Enterprise.

NGO uploads an existing printed receipt image or PDF.

The application allows:

- Background selection.
- Field placement.
- Donor name placement.
- Amount placement.
- Date placement.
- Receipt number placement.
- Signature placement.
- Font size.
- Alignment.
- Preview.
- Save one active custom template.

## 15.3 Advanced Custom Receipt Designer

Enterprise only.

Include:

- Multiple templates.
- Drag-and-drop editor.
- Conditional fields.
- Dynamic field mappings.
- Multiple layouts.
- Template duplication.
- Draft/published states.
- Maker-checker approval.
- Version history.
- Rollback.
- Advanced preview.
- Print-safe layouts.

## 15.4 Mobile editing requirements

The receipt designer must work on smartphone screens.

Use:

- Pinch-to-zoom.
- Drag handles.
- Alignment guides.
- Field property panels.
- Snap-to-grid.
- Undo/redo where practical.
- Preview mode.
- Save draft.
- Validation warnings.

Do not simply shrink a desktop editor.

## 15.5 PDF rendering

Use backend Java PDF generation.

Store templates as structured JSON.

Validate:

- Page dimensions.
- Field coordinates.
- Required fields.
- Unsupported fonts.
- Invalid mappings.
- Overlapping fields.
- Out-of-bounds placement.
- Template ownership.
- Plan entitlement.

## 15.6 Receipt lifecycle

PENDING
GENERATING
GENERATED
DELIVERY_PENDING
DELIVERED
GENERATION_FAILED
VOIDED
REISSUED

Support retry and recovery.

Preserve historical receipt versions.

Do not label every donation as 80G-eligible.

Support tax-related receipt information only when applicable NGO eligibility and required details have been verified.

---

# 16. NGO CAMPAIGNS AND PROJECTS

Campaigns:

- Create.
- Edit.
- Preview.
- Publish.
- Approve.
- Share.
- Track goals.
- Track donations.
- Close.
- Archive.
- Report.

Projects:

- Create.
- Assign staff.
- Set milestones.
- Define budgets.
- Track activities.
- Record expenses.
- Attach documents.
- Track progress.
- Close.
- Report.

Validate dates, budgets, permissions, status transitions, and tenant ownership.

---

# 17. BENEFICIARIES AND VOLUNTEERS

## Beneficiaries

Support:

- Registration.
- Profile.
- Eligibility information.
- Project assignment.
- Assistance records.
- Distribution history.
- Supporting documents.
- Privacy controls.
- Reporting.

Collect only necessary personal information.

Implement additional safeguards for children and vulnerable individuals.

## Volunteers

Support:

- Registration.
- Profile.
- Availability.
- Assignments.
- Attendance.
- Activity tracking.
- Volunteer hours.
- Reports.
- Deactivation.

Maintain NGO-specific records.

---

# 18. FINANCIAL MANAGEMENT

Implement:

- Income register.
- Expense register.
- Expense categories.
- Project budgets.
- Expense approvals.
- Attachments.
- Project expenditure.
- Financial summaries.
- Reconciliation.
- Reports.
- Exports.

Use immutable history for finalized transactions.

Support maker-checker controls for sensitive financial actions.

---

# 19. NGO HELPDESK AND QUERY TRACKER

Build two logically isolated helpdesk domains.

## 19.1 NGO Internal Helpdesk

Used for:

- Donation questions.
- Receipt requests.
- Refund requests.
- Volunteer issues.
- Beneficiary queries.
- Campaign queries.
- Staff operational issues.
- NGO grievances.

## 19.2 Platform Support Helpdesk

Used for:

- Login issues.
- Subscription issues.
- Staff limits.
- Payment problems.
- Receipt designer issues.
- Technical defects.
- Verification issues.
- Feature requests.
- Data export issues.

Do not allow platform support staff to browse private NGO internal tickets without explicit, authorized support access.

## 19.3 Ticket fields

- Ticket ID.
- Tenant ID.
- Requester.
- Category.
- Subject.
- Description.
- Priority.
- Status.
- Assigned agent.
- Assigned team.
- Related resource.
- Attachments.
- Created timestamp.
- SLA deadline.
- Resolution.
- Closed timestamp.
- Feedback.

## 19.4 Ticket statuses

NEW
OPEN
ASSIGNED
IN_PROGRESS
WAITING_FOR_REQUESTER
WAITING_FOR_INTERNAL_TEAM
ESCALATED
RESOLVED
CLOSED
REOPENED
CANCELLED

## 19.5 SLA

Support configurable business calendars and SLAs.

Initial proposed targets:

Low:
First response 48 business hours.
Resolution 5 business days.

Medium:
First response 24 business hours.
Resolution 3 business days.

High:
First response 8 business hours.
Resolution 2 business days.

Critical:
First response 2 business hours.
Resolution 8 business hours.

These are proposed targets, not guaranteed service commitments.

## 19.6 Ticket workflow

Create ticket.

Validate request.

Generate unique ticket number.

Assign queue.

Notify assigned team.

Track response SLA.

Allow public replies.

Allow private internal notes.

Escalate when required.

Record resolution.

Notify requester.

Allow feedback.

Support controlled reopening.

Maintain audit history.

## 19.7 Helpdesk reports

- Open tickets.
- Closed tickets.
- Overdue tickets.
- Tickets by priority.
- Tickets by category.
- Tickets by staff.
- Average first response.
- Average resolution.
- SLA compliance.
- Reopened tickets.
- Satisfaction rating.

---

# 20. NOTIFICATIONS

Create a centralized notification service.

Support:

- Email.
- In-app.
- Push.
- Optional SMS.
- Optional WhatsApp.

Use provider adapters.

Do not require paid SMS or WhatsApp integrations for initial local development.

Use a local email testing service.

Implement:

- Templates.
- Queues.
- Retries.
- Delivery status.
- Dead-letter handling.
- Communication preferences.
- Opt-outs.
- Audit history.

Notification events include:

- NGO registration.
- NGO approval/rejection.
- Staff invitation.
- Donation confirmation.
- Receipt delivery.
- Campaign updates.
- Subscription reminders.
- Subscription expiry.
- Payment failures.
- Helpdesk updates.
- SLA escalations.

---

# 21. VALIDATION REQUIREMENTS

For every field and API, implement:

1. Flutter validation.
2. Spring Boot DTO validation.
3. Domain validation.
4. Database constraints where applicable.
5. Authorization.
6. Tenant ownership.
7. Automated tests.

## 21.1 General rules

Validate:

- Required fields.
- Null values.
- Blank values.
- Length boundaries.
- Unicode.
- Invalid characters.
- Email format.
- Mobile format.
- PAN format where applicable.
- PIN code.
- Date ranges.
- Amount precision.
- Negative values.
- Zero values.
- Duplicate values.
- Unsupported enum values.
- Invalid identifiers.
- File size.
- File type.
- File ownership.
- Role permissions.
- Subscription entitlements.
- Invalid state transitions.
- Expired sessions.
- Concurrent updates.
- Duplicate requests.

## 21.2 Frontend behavior

Show inline errors.

Preserve valid entered values.

Focus the first invalid field.

Prevent duplicate submissions.

Handle API errors gracefully.

Show meaningful retry actions.

Preserve drafts where appropriate.

## 21.3 API errors

Use consistent error responses containing:

- Error code.
- Message.
- Field errors.
- Correlation ID.
- HTTP status.

Never expose stack traces or database details to users.

## 21.4 Validation catalog

Create:

`docs/testing/VALIDATION_MATRIX.md`

For every screen field, specify:

- Field name.
- Type.
- Required/optional.
- Minimum length.
- Maximum length.
- Format.
- Allowed values.
- Business rule.
- Backend validation.
- Database constraint.
- Test cases.

Do not merely write “validation implemented.”

Document the exact rule.

---

# 22. SECURITY AND PRIVACY

Follow applicable OWASP guidance.

Implement:

- Authentication.
- Authorization.
- Tenant isolation.
- Object-level authorization.
- Input validation.
- Rate limiting.
- Secure file uploads.
- Secret management.
- Encryption in transit.
- Secure token storage.
- Audit logging.
- Session revocation.
- Sensitive-action reauthentication.
- Secure error handling.
- Data export controls.
- Retention controls.

Implement privacy workflows for:

- Notice presentation.
- Communication preferences.
- Data access requests.
- Correction requests.
- Erasure requests.
- Grievances.
- Consent records where required.
- Retention.
- Legal holds.
- Processor obligations.

Do not claim automatic DPDP compliance.

Legal and regulatory applicability must be reviewed before production release.

Never log passwords, tokens, payment credentials, or unnecessary sensitive personal data.

---

# 23. DATABASE DESIGN

Use database-per-service ownership.

Required entities include:

Identity:

users
user_identities
ngo_memberships
roles
permissions
role_permissions
staff_invitations

NGO:

ngos
ngo_addresses
ngo_registrations
ngo_documents
ngo_verifications
ngo_contacts

Subscription:

subscription_plans
plan_entitlements
ngo_subscriptions
subscription_changes
subscription_addons
subscription_invoices
subscription_payments
subscription_credits
subscription_events

Donor:

donor_profiles
donor_ngo_relationships
donor_preferences
donor_consents

Payments:

donations
donation_payments
payment_events
payment_settlements
payment_reconciliations
refunds
ledger_accounts
journal_entries
journal_lines

Receipts:

receipt_templates
receipt_template_versions
receipt_template_approvals
donation_receipts
receipt_events

Operations:

campaigns
projects
project_milestones
beneficiaries
beneficiary_assistance
volunteers
volunteer_assignments
volunteer_attendance
expenses
expense_approvals

Helpdesk:

helpdesk_tickets
helpdesk_categories
helpdesk_messages
helpdesk_attachments
helpdesk_assignments
helpdesk_status_history
helpdesk_sla_policies
helpdesk_sla_events
helpdesk_escalations
helpdesk_feedback

Notifications:

notifications
notification_templates
notification_deliveries

Add:

audit_events
outbox_events
inbox_processed_events

Do not use cross-service foreign keys.

Use immutable identifiers and API/event references across service boundaries.

---

# 24. API DESIGN

Expose versioned REST APIs.

Use:

/api/v1/auth
/api/v1/ngos
/api/v1/staff
/api/v1/donors
/api/v1/donations
/api/v1/payments
/api/v1/receipts
/api/v1/campaigns
/api/v1/projects
/api/v1/beneficiaries
/api/v1/volunteers
/api/v1/finance
/api/v1/subscriptions
/api/v1/helpdesk
/api/v1/notifications
/api/v1/reports
/api/v1/admin

Generate OpenAPI specifications.

Use:

- Request DTOs.
- Response DTOs.
- Pagination.
- Filtering.
- Sorting.
- Validation.
- Authorization.
- Consistent errors.
- Idempotency keys for financial mutations.

Never expose JPA entities directly.

---

# 25. TESTING — MINIMUM 1,000 MEANINGFUL CASES

The product must include a substantial automated test suite.

Target at least 1,000 individually identifiable test cases across backend and mobile.

Suggested allocation:

| Area | Minimum tests |
|---|---:|
| Authentication and identity | 80 |
| NGO registration and verification | 80 |
| Tenant isolation and RBAC | 100 |
| Subscriptions and billing | 120 |
| Staff and administrators | 80 |
| Donors | 70 |
| Donations and payments | 120 |
| Financial ledger and reconciliation | 80 |
| Receipt designer and generation | 100 |
| Campaigns and projects | 60 |
| Volunteers, beneficiaries and finance | 60 |
| Helpdesk | 80 |
| Notifications and reports | 50 |
| Flutter UI and navigation | 100 |
| **Total target** | **1,200** |

The initial mandatory milestone is 500+ meaningful tests.

The full target is 1,000+.

The allocation above provides a 1,200-case planning backlog.

Do not write trivial tests to inflate counts.

## 25.1 Test types

- Unit.
- Service.
- Repository.
- API integration.
- Database integration.
- Security.
- Tenant isolation.
- Flutter widget.
- Mobile integration.
- End-to-end.
- Payment workflow.
- PDF generation.
- Concurrency.
- Regression.
- Performance.
- Resilience.

## 25.2 Test data

Use synthetic test data.

Create:

- Multiple NGOs.
- Multiple administrators.
- Multiple staff roles.
- Multiple donors.
- Guest donors.
- Multiple subscriptions.
- Expired subscriptions.
- Payment failures.
- Refunds.
- Receipt templates.
- Campaigns.
- Projects.
- Volunteers.
- Beneficiaries.
- Helpdesk tickets.

Never use real personal data in fixtures.

## 25.3 Mandatory critical journeys

Test:

1. NGO registers.
2. NGO submits documents.
3. NGO is verified.
4. NGO starts trial.
5. NGO purchases Community.
6. NGO adds first staff member.
7. Second staff member is rejected.
8. NGO upgrades to Growth.
9. Proration is correct.
10. Growth allows four staff.
11. NGO upgrades to Professional.
12. Basic receipt designer activates.
13. NGO upgrades to Enterprise.
14. Advanced receipt designer activates.
15. Enterprise allows 30 staff.
16. Enterprise purchases 10 additional seats.
17. Capacity increases to 40.
18. Donor registers.
19. Donor discovers NGO.
20. Donor initiates donation.
21. Mock payment succeeds.
22. Webhook verifies payment.
23. Donation becomes paid.
24. Receipt is generated.
25. Receipt is delivered.
26. Duplicate webhook is ignored.
27. Receipt generation failure is recovered.
28. NGO creates campaign.
29. NGO creates project.
30. NGO records expenses.
31. Volunteer is assigned.
32. Beneficiary receives assistance.
33. NGO staff creates helpdesk ticket.
34. Ticket is assigned.
35. SLA countdown works.
36. Ticket escalates.
37. Ticket is resolved.
38. NGO subscription expires.
39. Grace period begins.
40. NGO becomes read-only.
41. NGO returns after five months.
42. NGO renews.
43. Existing data is preserved.
44. Another NGO cannot access the records.
45. Unauthorized staff cannot modify subscriptions.

## 25.4 Test reporting

Generate:

- Test inventory.
- Executed tests.
- Passed.
- Failed.
- Skipped.
- Blocked.
- Coverage.
- Defects.
- Security findings.
- Performance results.

Do not claim tests passed without execution evidence.

---

# 26. OBSERVABILITY

Implement:

- Structured JSON logs.
- Correlation IDs.
- Distributed tracing.
- OpenTelemetry.
- Metrics.
- Health checks.
- Readiness checks.
- Error tracking.
- Queue monitoring.
- Database monitoring.

Use instrumentation rather than manually logging every function.

Support tracing across microservices.

Never log secrets or unnecessary personal information.

---

# 27. LOCAL DEPLOYMENT

Provide Podman-compatible local deployment.

Required services:

- API Gateway.
- All Spring Boot microservices.
- PostgreSQL.
- Keycloak.
- RabbitMQ.
- Object storage.
- Local email testing.
- Observability components where practical.

Provide scripts for:

- Start.
- Stop.
- Reset development data.
- Run migrations.
- Seed sample data.
- Run backend tests.
- Run Flutter tests.
- Build Android.
- Build iOS where tooling exists.

Use environment variables and secret files.

Do not commit production secrets.

Provide working health checks.

---

# 28. KUBERNETES AND OPENSHIFT

Create deployment manifests for:

- Services.
- Deployments.
- ConfigMaps.
- Secrets references.
- Persistent storage.
- Ingress/Routes.
- Resource requests.
- Resource limits.
- Readiness probes.
- Liveness probes.
- Horizontal scaling where appropriate.

Support OpenShift restricted security contexts.

Do not assume root privileges.

Do not require privileged containers.

Document storage and database deployment recommendations separately from application services.

---

# 29. ANDROID RELEASE

Prepare:

- Android release build.
- App signing configuration.
- Android App Bundle.
- Package identifier.
- Versioning.
- Deep links.
- Notification permissions.
- Camera/file permissions.
- Privacy disclosures.
- Store listing checklist.
- Test accounts.
- Release notes.
- Upgrade testing.

Do not claim Play Store publication unless the actual submission and approval have occurred.

---

# 30. IOS RELEASE

Prepare:

- iOS release build.
- Bundle identifier.
- Signing configuration.
- Provisioning instructions.
- Universal links.
- Push notification configuration.
- Required permission descriptions.
- Privacy manifest review.
- App Store metadata.
- TestFlight checklist.
- Release notes.
- Account deletion workflow.
- Sign in with Apple where applicable.

An actual signed iOS release requires appropriate Apple tooling, certificates, provisioning, and developer account access.

If unavailable, produce all feasible source, configuration, and documentation, and clearly identify the remaining release blockers.

Do not claim App Store publication without actual submission and approval.

---

# 31. APP-STORE BILLING AND CHARITABLE DONATIONS

This requires special attention.

The application sells access to digital NGO SaaS functionality.

Platform-specific app-store billing rules may apply.

Charitable donations may be treated differently from digital SaaS purchases.

Before enabling production billing:

1. Verify applicable Apple policies.
2. Verify applicable Google Play policies.
3. Determine permitted subscription payment methods.
4. Determine permitted donation payment methods.
5. Separate SaaS subscription checkout from charitable donation checkout.
6. Implement appropriate provider adapters.
7. Document required approvals and restrictions.

For local development, use mock billing adapters.

Do not assume that the same external gateway flow is permitted for every payment type on both platforms.

---

# 32. DEVELOPMENT EXECUTION STRATEGY

Do not attempt to implement the entire application in one uncontrolled pass.

Use incremental, verifiable development.

## Phase 0 — Repository assessment

Inspect the repository.

Identify existing code.

Create architecture documentation.

Create implementation backlog.

Create screen inventory.

Create validation matrix.

Create database ownership matrix.

Create API contracts.

Create event contracts.

Create test plan.

## Phase 1 — Foundation

Flutter application.

Spring Boot microservices skeleton.

API Gateway.

PostgreSQL.

Keycloak.

RabbitMQ.

Object storage.

Podman deployment.

Design system.

Logging and tracing.

## Phase 2 — Identity and NGO onboarding

Authentication.

NGO registration.

Verification.

Staff.

Administrators.

RBAC.

Tenant isolation.

## Phase 3 — Subscriptions

Plan catalog.

Trial.

Quarterly billing.

Annual billing.

Upgrade.

Downgrade.

Enterprise staff blocks.

Expiry.

Reactivation.

## Phase 4 — Donors and donations

Donor management.

Donor discovery.

Guest donations.

Mock payments.

Payment events.

Reconciliation.

Standard receipts.

## Phase 5 — NGO operations

Campaigns.

Projects.

Volunteers.

Beneficiaries.

Expenses.

Dashboards.

Reports.

## Phase 6 — Receipt designer

Basic designer.

Advanced designer.

PDF generation.

Template approval.

Versioning.

Failure recovery.

## Phase 7 — Helpdesk

NGO internal tickets.

Platform support tickets.

Assignment.

SLA.

Escalation.

Notifications.

Reports.

## Phase 8 — Quality

Complete automated tests.

Security assessment.

Performance testing.

Resilience testing.

Accessibility testing.

Android/iOS device testing.

## Phase 9 — Release preparation

Android release artifacts.

iOS release preparation.

Deployment manifests.

Operational documentation.

Production-readiness report.

---

# 33. TOKEN-EFFICIENT EXECUTION RULES

The product owner wants to minimize unnecessary AI token consumption without sacrificing quality.

Follow these rules:

1. Read existing files before creating replacements.
2. Reuse existing working functionality.
3. Avoid repeated architecture explanations.
4. Maintain a concise implementation status file.
5. Keep decisions in version-controlled documentation.
6. Use code generation where appropriate.
7. Use reusable Flutter components.
8. Use reusable validation patterns.
9. Avoid duplicating business logic.
10. Execute automated tests rather than reasoning about whether code works.
11. Report only relevant implementation results.
12. Avoid rewriting entire files unnecessarily.
13. Fix root causes rather than repeatedly applying temporary patches.
14. Do not repeatedly ask questions already answered in this specification.
15. Continue from the implementation backlog after context resets.

Create:

`CLAUDE.md`

This file must contain a concise project overview, architecture, coding conventions, key business rules, repository commands, and links to authoritative specifications.

Keep `CLAUDE.md` concise.

Store detailed requirements in separate documentation files.

Create:

`IMPLEMENTATION_STATUS.md`

Track:

- Completed modules.
- Current module.
- Tests executed.
- Known blockers.
- Next implementation steps.

Do not rely on conversation memory for project continuity.

---

# 34. DEFINITION OF DONE

A module is complete only when:

1. Its required screens exist.
2. Navigation works.
3. Backend APIs exist.
4. Database migrations exist.
5. Authorization works.
6. Tenant isolation works.
7. Field validation works.
8. Business validation works.
9. Error handling works.
10. Loading states work.
11. Empty states work.
12. Automated tests exist.
13. Relevant tests pass.
14. Audit requirements are met.
15. Documentation is updated.

A feature is not complete merely because its UI renders.

A payment workflow is not complete merely because the mock provider returns success.

A release is not complete merely because the application compiles.

---

# 35. FINAL ACCEPTANCE CRITERIA

Before declaring the project ready for release:

- Flutter builds successfully.
- Android build is verified.
- iOS build is verified where tooling permits.
- Spring Boot services compile.
- PostgreSQL migrations succeed.
- Keycloak authentication works.
- NGO registration works.
- NGO verification works.
- Tenant isolation works.
- Staff limits work.
- Subscription rules work.
- Upgrades and downgrades work.
- Enterprise staff blocks work.
- Expiry and reactivation work.
- Donor management works.
- Donation workflows work.
- Mock payments work.
- Financial reconciliation works.
- Standard receipts work.
- Basic custom receipt designer works.
- Advanced custom receipt designer works.
- Campaign management works.
- Project management works.
- Volunteer management works.
- Beneficiary management works.
- Expense management works.
- NGO helpdesk works.
- Platform support helpdesk works.
- Notifications work.
- Reports work.
- Security tests pass.
- Critical defects are resolved.
- Deployment is reproducible.
- Documentation is complete.
- External integration blockers are identified.

Do not claim 100% completion when critical acceptance criteria remain unverified.

---

# 36. REQUIRED FINAL DELIVERABLES

Deliver:

1. Complete Flutter source code.
2. Android release build artifacts.
3. iOS source and release artifacts where supported.
4. All Spring Boot microservices.
5. PostgreSQL migrations.
6. Keycloak configuration.
7. RabbitMQ configuration.
8. Object storage integration.
9. Podman deployment.
10. Kubernetes/OpenShift manifests.
11. OpenAPI specifications.
12. Database design documentation.
13. Security architecture.
14. Subscription rules.
15. Payment ledger design.
16. Receipt designer documentation.
17. Helpdesk documentation.
18. Validation matrix.
19. Screen inventory.
20. Automated test suite.
21. Test execution reports.
22. Security scan results.
23. Performance results.
24. Dependency/license inventory.
25. SBOM.
26. Android release checklist.
27. iOS release checklist.
28. Production-readiness report.
29. Known limitations.
30. Implementation completion matrix.

---

# 37. FINAL EXECUTION INSTRUCTION

You are building a commercial product intended for real Indian NGOs and donors.

Treat security, financial correctness, user trust, data preservation, and maintainability as mandatory.

Do not rush implementation merely to report completion.

Do not leave disconnected screens.

Do not silently skip requirements.

Do not invent external integrations.

Do not use real payment credentials in development fixtures.

Do not publish applications or initiate production financial transactions without explicit authorization.

Do not delete existing working code without understanding its purpose.

Do not fabricate test results.

If a requirement cannot be implemented because of missing credentials, unavailable tools, unresolved licensing, legal review, or external provider restrictions:

1. Implement the feasible portion.
2. Create a clearly isolated development adapter where appropriate.
3. Document the blocker.
4. Document the exact remaining action.
5. Continue with independent work.

**START NOW:**

1. Inspect the current repository.
2. Create `CLAUDE.md`.
3. Create the architecture and implementation backlog.
4. Create the complete screen inventory.
5. Create the detailed validation matrix.
6. Identify conflicts or missing business decisions.
7. Implement Phase 1.
8. Run tests.
9. Fix failures.
10. Continue incrementally according to the implementation plan.

Your objective is not to produce an impressive development summary.

**Your objective is to deliver a functioning, tested, maintainable NGO SaaS product for Android and iPhone, backed by secure Spring Boot microservices and PostgreSQL.**