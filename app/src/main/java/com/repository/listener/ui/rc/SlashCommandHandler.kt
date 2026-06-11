package com.repository.listener.ui.rc

import android.content.Intent
import androidx.appcompat.app.AlertDialog
import com.repository.listener.service.ListenerService
import java.util.UUID

// --- Result types ---

sealed class CommandResult {
    data class ShowMessage(val msg: RcMessage) : CommandResult()
    data class ShowDialog(val builder: (RemoteControlActivity) -> Unit) : CommandResult()
    data class ForwardToDesktop(val text: String) : CommandResult()
    object Handled : CommandResult()
}

// --- Base handler ---

abstract class SlashCommandHandler(val name: String, val description: String) {
    abstract fun execute(
        activity: RemoteControlActivity,
        args: String,
        callback: (CommandResult) -> Unit
    )

    protected fun systemMessage(text: String): RcMessage.SessionEvent =
        RcMessage.SessionEvent(
            id = UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
            event = text
        )

    protected fun sendSettingChange(
        activity: RemoteControlActivity,
        setting: String,
        value: String
    ) {
        activity.sendBroadcast(Intent(ListenerService.ACTION_RC_SETTING_CHANGE).apply {
            setPackage(activity.packageName)
            putExtra(ListenerService.EXTRA_RC_SESSION_ID, activity.getSessionId())
            putExtra("rc_setting", setting)
            putExtra("rc_value", value)
        })
    }
}

// --- Registry ---

object SlashCommandRegistry {
    private val handlers = LinkedHashMap<String, SlashCommandHandler>()

    fun register(handler: SlashCommandHandler) {
        handlers[handler.name] = handler
    }

    fun get(name: String): SlashCommandHandler? = handlers[name]

    fun all(): List<SlashCommandHandler> = handlers.values.toList()

    fun init() {
        handlers.clear()

        // Phone-local commands
        register(ModelCommand())
        register(EffortCommand())
        register(FastCommand())
        register(ContextCommand())
        register(StatusCommand())
        register(HelpCommand())
        register(ExitCommand())
        register(PermissionsCommand())

        // Blocked commands
        for (name in listOf("mcp", "config", "memory", "tasks", "theme", "plan")) {
            register(BlockedCommand(name))
        }

        // Bridge-safe commands
        register(CompactCommand())
        register(ClearCommand())
        register(CostCommand())
        register(FilesCommand())

        // Prompt/skill commands (AI processes)
        register(ForwardCommand("commit", "Create a git commit"))
        register(ForwardCommand("review", "Review code changes"))
        register(ForwardCommand("diff", "Show diff of changes"))
        register(ForwardCommand("simplify", "Simplify selected code"))
        register(ForwardCommand("resume", "Resume a previous task"))
    }
}

// --- Phone-local command implementations ---

private class ModelCommand : SlashCommandHandler("model", "Switch the AI model") {
    private val models = linkedMapOf(
        "opus" to "claude-opus-4-6[1m]",
        "sonnet" to "claude-sonnet-4-6",
        "haiku" to "claude-haiku-4-5-20251001"
    )

    override fun execute(
        activity: RemoteControlActivity,
        args: String,
        callback: (CommandResult) -> Unit
    ) {
        val labels = models.keys.toTypedArray()
        callback(CommandResult.ShowDialog { act ->
            AlertDialog.Builder(act)
                .setTitle("Select model")
                .setItems(labels) { _, which ->
                    val label = labels[which]
                    val modelId = models[label]!!
                    sendSettingChange(act, "model", modelId)
                    act.getAdapter().addMessage(systemMessage("Model changed to $label ($modelId)"))
                }
                .show()
        })
    }
}

private class EffortCommand : SlashCommandHandler("effort", "Set reasoning effort level") {
    private val levels = arrayOf("low", "medium", "high", "max")

    override fun execute(
        activity: RemoteControlActivity,
        args: String,
        callback: (CommandResult) -> Unit
    ) {
        callback(CommandResult.ShowDialog { act ->
            AlertDialog.Builder(act)
                .setTitle("Select effort level")
                .setItems(levels) { _, which ->
                    val level = levels[which]
                    sendSettingChange(act, "effort", level)
                    act.getAdapter().addMessage(systemMessage("Effort set to $level"))
                }
                .show()
        })
    }
}

private class FastCommand : SlashCommandHandler("fast", "Toggle fast mode") {
    override fun execute(
        activity: RemoteControlActivity,
        args: String,
        callback: (CommandResult) -> Unit
    ) {
        val newState = !activity.isFastMode
        activity.isFastMode = newState
        sendSettingChange(activity, "fast", newState.toString())
        callback(CommandResult.ShowMessage(
            systemMessage("Fast mode ${if (newState) "ON" else "OFF"}")
        ))
    }
}

private class ContextCommand : SlashCommandHandler("context", "Show context window usage") {
    override fun execute(
        activity: RemoteControlActivity,
        args: String,
        callback: (CommandResult) -> Unit
    ) {
        val pct = activity.getContextPct()
        callback(CommandResult.ShowMessage(
            systemMessage("Context: ${pct}% used")
        ))
    }
}

private class StatusCommand : SlashCommandHandler("status", "Show current session status") {
    override fun execute(
        activity: RemoteControlActivity,
        args: String,
        callback: (CommandResult) -> Unit
    ) {
        val lines = listOf(
            "Model: ${activity.currentModel}",
            "Work dir: ${activity.getWorkDir()}",
            "Context: ${activity.getContextPct()}% used",
            "Effort: ${activity.currentEffort}",
            "Fast mode: ${if (activity.isFastMode) "ON" else "OFF"}"
        )
        callback(CommandResult.ShowMessage(systemMessage(lines.joinToString("\n"))))
    }
}

private class HelpCommand : SlashCommandHandler("help", "List all available commands") {
    override fun execute(
        activity: RemoteControlActivity,
        args: String,
        callback: (CommandResult) -> Unit
    ) {
        val lines = SlashCommandRegistry.all().joinToString("\n") { handler ->
            "/${handler.name} -- ${handler.description}"
        }
        callback(CommandResult.ShowMessage(systemMessage(lines)))
    }
}

private class ExitCommand : SlashCommandHandler("exit", "End the session") {
    override fun execute(
        activity: RemoteControlActivity,
        args: String,
        callback: (CommandResult) -> Unit
    ) {
        activity.finish()
        callback(CommandResult.Handled)
    }
}

private class PermissionsCommand : SlashCommandHandler("permissions", "Open permission mode selector") {
    override fun execute(
        activity: RemoteControlActivity,
        args: String,
        callback: (CommandResult) -> Unit
    ) {
        activity.showModePopup()
        callback(CommandResult.Handled)
    }
}

// --- Blocked commands ---

private class BlockedCommand(name: String) :
    SlashCommandHandler(name, "Not available on mobile") {
    override fun execute(
        activity: RemoteControlActivity,
        args: String,
        callback: (CommandResult) -> Unit
    ) {
        callback(CommandResult.ShowMessage(
            systemMessage("/$name is not available on mobile")
        ))
    }
}

// --- Bridge-safe commands ---

private class CompactCommand : SlashCommandHandler("compact", "Compact conversation context") {
    override fun execute(
        activity: RemoteControlActivity,
        args: String,
        callback: (CommandResult) -> Unit
    ) {
        callback(CommandResult.ForwardToDesktop("/compact"))
    }
}

private class ClearCommand : SlashCommandHandler("clear", "Clear conversation") {
    override fun execute(
        activity: RemoteControlActivity,
        args: String,
        callback: (CommandResult) -> Unit
    ) {
        activity.getAdapter().submitMessages(emptyList())
        callback(CommandResult.ForwardToDesktop("/clear"))
    }
}

private class CostCommand : SlashCommandHandler("cost", "Show session cost") {
    override fun execute(
        activity: RemoteControlActivity,
        args: String,
        callback: (CommandResult) -> Unit
    ) {
        callback(CommandResult.ForwardToDesktop("/cost"))
    }
}

private class FilesCommand : SlashCommandHandler("files", "List files in context") {
    override fun execute(
        activity: RemoteControlActivity,
        args: String,
        callback: (CommandResult) -> Unit
    ) {
        callback(CommandResult.ForwardToDesktop("/files"))
    }
}

// --- Prompt/skill commands (forwarded with args) ---

private class ForwardCommand(name: String, description: String) :
    SlashCommandHandler(name, description) {
    override fun execute(
        activity: RemoteControlActivity,
        args: String,
        callback: (CommandResult) -> Unit
    ) {
        val text = if (args.isBlank()) "/$name" else "/$name $args"
        callback(CommandResult.ForwardToDesktop(text))
    }
}
