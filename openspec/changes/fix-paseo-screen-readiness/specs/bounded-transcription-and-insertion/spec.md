## ADDED Requirements

### Requirement: Screen-based composer discovery
Android SHALL identify the focused native input and its accessible submit controls
without requiring application view IDs, placeholder text, shape or fixed position.
It SHALL refresh controls before selection and activation, exclude actions outside
the nearest exposed input group, and refuse ambiguous actions.

#### Scenario: No application composer ID
- **WHEN** a focused input and unique local Send exist without application IDs
- **THEN** insertion and one submission remain available

#### Scenario: Input label changes
- **WHEN** the focused native editor has a different accessible label
- **THEN** its identity and exact draft echo establish eligibility

### Requirement: Independent echo and Send readiness
Android SHALL bound insertion echo separately from Send readiness. It SHALL start
the five-second readiness allowance once at the first exact echo, reread live
state on events or bounded polling, and activate a ready Send immediately without
a minimum wait. It SHALL NOT activate Stop or extend readiness on repeated events.

#### Scenario: Delayed Stop-to-Send transition
- **WHEN** Stop becomes an enabled Send three seconds after confirmed insertion
- **THEN** fresh semantics permit one immediate submission

#### Scenario: Stale cached Stop or disabled state
- **WHEN** discovery returns a cached snapshot of a now-ready control
- **THEN** native refresh determines eligibility before any activation

#### Scenario: Send already ready
- **WHEN** exact insertion echo and an enabled Send are available immediately
- **THEN** no readiness timer delay precedes activation
