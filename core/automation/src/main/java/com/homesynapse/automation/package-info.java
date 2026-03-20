/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */

/**
 * Automation engine: trigger-condition-action rules, cascade governor, command
 * dispatch, and pending command tracking.
 *
 * <p>This package defines the public API for the HomeSynapse automation subsystem,
 * implementing the Trigger-Condition-Action (TCA) model described in Doc 07. It
 * includes four sealed type hierarchies ({@link com.homesynapse.automation.TriggerDefinition},
 * {@link com.homesynapse.automation.ConditionDefinition},
 * {@link com.homesynapse.automation.ActionDefinition},
 * {@link com.homesynapse.automation.Selector}), data records for automation definitions
 * and execution context, and service interfaces for trigger evaluation, condition
 * checking, action execution, command dispatch, and conflict detection.</p>
 *
 * <p>The automation module is the most cross-cutting subsystem after the Event Model,
 * consuming contracts from the Event Bus, Device Model, State Store, Configuration
 * System, and Identity Model.</p>
 *
 * @see com.homesynapse.automation.AutomationDefinition
 * @see com.homesynapse.automation.RunManager
 * @see com.homesynapse.automation.TriggerEvaluator
 */
package com.homesynapse.automation;
