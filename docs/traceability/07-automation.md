# Traceability: automation

## automation module (com.homesynapse.automation)

| Design Doc Section | Interface / Type | Test Class |
|--------------------|-----------------|------------|
| Doc 07 §3.6 | ConcurrencyMode | Phase 3 |
| Doc 07 §3.7 | RunStatus | Phase 3 |
| Doc 07 §4.3 | PendingStatus | Phase 3 |
| Doc 07 §3.9 | UnavailablePolicy | Phase 3 |
| Doc 07 §3.3 | MaxExceededSeverity | Phase 3 |
| Doc 07 §8.2 | RunId | Phase 3 |
| Doc 07 §3.12 | Selector | Phase 3 |
| Doc 07 §3.12 | DirectRefSelector | Phase 3 |
| Doc 07 §3.12 | SlugSelector | Phase 3 |
| Doc 07 §3.12 | AreaSelector | Phase 3 |
| Doc 07 §3.12 | LabelSelector | Phase 3 |
| Doc 07 §3.12 | TypeSelector | Phase 3 |
| Doc 07 §3.12 | CompoundSelector | Phase 3 |
| Doc 07 §3.4 | TriggerDefinition | Phase 3 |
| Doc 07 §3.4 | StateChangeTrigger | Phase 3 |
| Doc 07 §3.4 | StateTrigger | Phase 3 |
| Doc 07 §3.4 | EventTrigger | Phase 3 |
| Doc 07 §3.4 | AvailabilityTrigger | Phase 3 |
| Doc 07 §3.4 | NumericThresholdTrigger | Phase 3 |
| Doc 07 §8.2 | TimeTrigger | Phase 3 |
| Doc 07 §8.2 | SunTrigger | Phase 3 |
| Doc 07 §8.2 | PresenceTrigger | Phase 3 |
| Doc 07 §8.2 | WebhookTrigger | Phase 3 |
| Doc 07 §3.8 | ConditionDefinition | Phase 3 |
| Doc 07 §3.8 | StateCondition | Phase 3 |
| Doc 07 §3.8 | NumericCondition | Phase 3 |
| Doc 07 §3.8 | TimeCondition | Phase 3 |
| Doc 07 §3.8 | AndCondition | Phase 3 |
| Doc 07 §3.8 | OrCondition | Phase 3 |
| Doc 07 §3.8 | NotCondition | Phase 3 |
| Doc 07 §8.2 | ZoneCondition | Phase 3 |
| Doc 07 §3.9 | ActionDefinition | Phase 3 |
| Doc 07 §3.9 | CommandAction | Phase 3 |
| Doc 07 §3.9 | DelayAction | Phase 3 |
| Doc 07 §3.9 | WaitForAction | Phase 3 |
| Doc 07 §3.9 | ConditionBranchAction | Phase 3 |
| Doc 07 §3.9 | EmitEventAction | Phase 3 |
| Doc 07 §8.2 | ActivateSceneAction | Phase 3 |
| Doc 07 §8.2 | InvokeIntegrationAction | Phase 3 |
| Doc 07 §8.2 | ParallelAction | Phase 3 |
| Doc 07 §3.3 / §4.1 | AutomationDefinition | Phase 3 |
| Doc 07 §8.2 | RunContext | Phase 3 |
| Doc 07 §4.3 | PendingCommand | Phase 3 |
| Doc 07 §8.2 | DurationTimer | Phase 3 |
| Doc 07 §8.1 | AutomationRegistry | Phase 3 |
| Doc 07 §3.4 / §8.1 | TriggerEvaluator | Phase 3 |
| Doc 07 §3.8 / §8.1 | ConditionEvaluator | Phase 3 |
| Doc 07 §3.9 / §8.1 | ActionExecutor | Phase 3 |
| Doc 07 §3.7 / §8.1 | RunManager | Phase 3 |
| Doc 07 §3.11.1 / §8.1 | CommandDispatchService | Phase 3 |
| Doc 07 §3.11.2 / §8.1 | PendingCommandLedger | Phase 3 |
| Doc 07 §3.12 / §8.1 | SelectorResolver | Phase 3 |
| Doc 07 §3.13 / §8.1 | ConflictDetector | Phase 3 |
