package com.company.aidev.tool;

import com.company.aidev.sandbox.Sandbox;
import java.util.UUID;

/**
 * Everything a sandbox-scoped tool needs to run and to be audited.
 *
 * <p>Tools are created per agent execution, not as singletons: a tool instance is bound to exactly
 * one sandbox, so there is no way for a tool call to reach another ticket's workspace.
 */
public record ToolContext(UUID workflowId, UUID agentExecutionId, Sandbox sandbox) {}
