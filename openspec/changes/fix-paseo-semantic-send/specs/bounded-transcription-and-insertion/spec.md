## ADDED Requirements

### Requirement: Semantic composer ownership
Android SHALL identify Paseo's primary action using the named composer ancestor
and native editor identity. It SHALL refresh the owned subtree before evaluating
readiness and SHALL NOT use button shape, spacing, screen position or bounds
relative to the editor as selection requirements. Missing ownership or ambiguous
actions SHALL prevent activation and produce specific feedback.

#### Scenario: Same composer changes layout
- **WHEN** the primary action moves within the named composer
- **THEN** the focused exact-echoed draft remains eligible for one submission

#### Scenario: Cached Send readiness is stale
- **WHEN** child enumeration returns an old disabled snapshot of an enabled control
- **THEN** the actual native node is refreshed before readiness is decided

#### Scenario: Global Send control
- **WHEN** an exact Send label exists outside the named composer
- **THEN** it is excluded regardless of proximity or position

### Requirement: Semantic activation without duplicate fallback
Android SHALL prefer ACTION_CLICK on the validated primary action. Only rejected
activation SHALL permit a native tap of that same freshly validated control.
Accepted activation SHALL NOT be retried. Unchanged draft through the separate
confirmation deadline SHALL produce unconfirmed-send feedback.

#### Scenario: Native action invokes React Native handler
- **WHEN** ACTION_CLICK is accepted and the mock handler resets the expected draft
- **THEN** local submission is confirmed and no touch fallback occurs

#### Scenario: Accepted action is ineffective
- **WHEN** an accepted action leaves the expected draft unchanged
- **THEN** automation reports unconfirmed send without another activation

#### Scenario: Rejected action
- **WHEN** Android rejects ACTION_CLICK before activation
- **THEN** one gesture may activate the validated live control
