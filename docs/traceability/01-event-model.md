# Traceability: event-model

## event module (com.homesynapse.event)

| Design Doc Section | Interface / Type | Test Class |
|--------------------|-----------------|------------|
| Doc 01 §4.1 | EventEnvelope | Phase 3 |
| Doc 01 §4.1 | EventId | Phase 3 |
| Doc 01 §4.1 / §8.3 | CausalContext | Phase 3 |
| Doc 01 §3.10 | DegradedEvent | Phase 3 |
| Doc 01 §3.10 | DomainEvent | Phase 3 |
| Doc 01 §4.3 | EventTypes | Phase 3 |
| Doc 01 §4.3 | SubjectRef | Phase 3 |
| Doc 01 §4.3 | SubjectType | Phase 3 |
| Doc 01 §3.3 | EventPriority | Phase 3 |
| Doc 01 §3.9 | EventOrigin | Phase 3 |
| Doc 01 §4.4 / A-01-DR-1 | EventCategory | Phase 3 |
| Doc 01 §3.7 | ProcessingMode | Phase 3 |
| Doc 01 §3.7 | CommandIdempotency | Phase 3 |
| Doc 01 §8.3 | EventPublisher | Phase 3 |
| Doc 01 §8.3 | EventDraft | Phase 3 |
| Doc 01 §8.1 / §3.4 | EventStore | Phase 3 |
| Doc 01 §8.1 / §3.4 | EventPage | Phase 3 |
| Doc 01 §6.7 | SequenceConflictException | Phase 3 |
| Doc 01 §4.6 | CommandIssuedEvent | Phase 3 |
| Doc 01 §4.6 | CommandDispatchedEvent | Phase 3 |
| Doc 01 §4.6 | CommandResultEvent | Phase 3 |
| Doc 01 §4.6 | CommandConfirmationTimedOutEvent | Phase 3 |
| Doc 01 §4.6 | StateReportedEvent | Phase 3 |
| Doc 01 §4.6 | StateChangedEvent | Phase 3 |
| Doc 01 §4.6 | StateConfirmedEvent | Phase 3 |
| Doc 01 §4.6 | StateReportRejectedEvent | Phase 3 |
| Doc 01 §4.3 | DeviceDiscoveredEvent | Phase 3 |
| Doc 01 §4.3 | DeviceAdoptedEvent | Phase 3 |
| Doc 01 §4.3 | DeviceRemovedEvent | Phase 3 |
| Doc 01 §4.3 | AvailabilityChangedEvent | Phase 3 |
| Doc 01 §4.3 | AutomationTriggeredEvent | Phase 3 |
| Doc 01 §4.3 | AutomationCompletedEvent | Phase 3 |
| Doc 01 §4.3 | PresenceSignalEvent | Phase 3 |
| Doc 01 §4.3 | PresenceChangedEvent | Phase 3 |
| Doc 01 §4.3 | SystemStartedEvent | Phase 3 |
| Doc 01 §4.3 | SystemStoppedEvent | Phase 3 |
| Doc 01 §4.3 | ConfigChangedEvent | Phase 3 |
| Doc 01 §4.3 | ConfigErrorEvent | Phase 3 |
| Doc 01 §4.3 | StoragePressureChangedEvent | Phase 3 |
| Doc 01 §4.3 | TelemetrySummaryEvent | Phase 3 |

## event-bus module (com.homesynapse.eventbus)

| Design Doc Section | Interface / Type | Test Class |
|--------------------|-----------------|------------|
| Doc 01 §3.4 | SubscriptionFilter | Phase 3 |
| Doc 01 §3.4 | SubscriberInfo | Phase 3 |
| Doc 01 §3.4 | EventBus | Phase 3 |
| Doc 01 §3.4 | CheckpointStore | Phase 3 |
