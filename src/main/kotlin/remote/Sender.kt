package remote

/** Delivers a command through the resident input helper, or the slower adb shell when it's down. */
class Sender(private val input: InputServer, private val shell: AdbShell, private val commands: ShellCommands) {
    suspend fun deliver(command: ProjectorCommand) {
        if (!input.run(command)) shell.send(commands.lineFor(command))
    }

    suspend fun press(key: RemoteKey) = deliver(ProjectorCommand.Key(key))
}
