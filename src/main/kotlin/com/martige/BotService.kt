package com.martige

import com.dropbox.core.DbxRequestConfig
import com.dropbox.core.NetworkIOException
import com.dropbox.core.v2.DbxClientV2
import com.dropbox.core.v2.files.ListFolderResult
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.martige.model.DatHostGameServer
import com.martige.model.DatHostPathsItem
import com.martige.model.UploadQueueItem
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.entities.User
import net.dv8tion.jda.api.entities.VoiceChannel
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.*
import java.util.concurrent.TimeUnit


class BotService(props: Properties, private var jda: JDA) {
    private var gameServerIp: String = props.getProperty("gameserver.ip")
    private var gameServerId = props.getProperty("gameserver.id")
    private var auth64String = "Basic " + Base64.getEncoder()
        .encodeToString(
            "${props.getProperty("dathost.username")}:${props.getProperty("dathost.password")}"
                .toByteArray()
        )
    private var dmTemplate =
        props.getProperty("dm.template") ?: "Your scrim server is ready! Paste this into your console:"
    private var discordPrivilegeRoleId = props.getProperty("discord.role.privilege.id").toLong()
    private var discordVoiceChannelId = props.getProperty("discord.voicechannel.id").toLong()
    private var discordTextChannelId: Long = props.getProperty("discord.textchannel.id").toLong()
    private val log: Logger = LoggerFactory.getLogger(BotService::class.java)
    private var queue: ArrayList<User> = arrayListOf()
    private val httpClient = OkHttpClient.Builder()
        .readTimeout(10, TimeUnit.MINUTES)
        .writeTimeout(10, TimeUnit.MINUTES)
        .callTimeout(5, TimeUnit.MINUTES)
        .build()
    private var autoclearHour = props.getProperty("bot.autoclear.hourofday") ?: "7"
    private var config: DbxRequestConfig = DbxRequestConfig.newBuilder("dropbox/mert-scrim-bot").build()
    private lateinit var dropboxClient: DbxClientV2
    private var dropboxToken = props.getProperty("dropbox.token")
    private var dropboxDemosFolder = props.getProperty("dropbox.sharedfolder") ?: ""
    private var autoUpload = props.getProperty("dropbox.upload.auto") ?: "true"
    private var uploadQueue: ArrayList<UploadQueueItem> = arrayListOf()
    private var isServerStarting = false

    fun addToQueue(event: MessageReceivedEvent) {
        if (queue.size == 10) {
            event.channel.sendMessage("10 players are ready! Type !start to launch the scrim server").queue()
        } else {
            if (!queue.contains(event.author)) {
                queue.add(event.author)
                event.channel.sendMessage("<@${event.author.id}> has joined the queue! Queue size: ${queue.size}/10")
                    .queue()
            } else {
                event.channel.sendMessage("<@${event.author.id}> is already in the queue").queue()
            }
        }
    }

    fun removeFromQueue(event: MessageReceivedEvent) {
        if (queue.contains(event.author)) {
            queue.remove(event.author)
            event.channel.sendMessage("<@${event.author.id}> has left the queue, see ya! Queue size ${queue.size}/10")
                .queue()
        } else {
            event.channel.sendMessage("Hey <@${event.author.id}>, you're not in the queue. Type `!join` to queue")
                .queue()
        }
    }

    fun listQueue(event: MessageReceivedEvent) {
        if (queue.size == 0) {
            event.channel.sendMessage("The queue is currently empty").queue()
            return
        }
        val stringBuilder = StringBuilder().appendln("The following users are queued: (${queue.size}/10)")
        queue.forEach { stringBuilder.appendln("- @${it.name}") }
        event.channel.sendMessage(stringBuilder.toString()).queue()
    }

    fun clearQueue(event: MessageReceivedEvent) {
        if (!isMemberPrivileged(event)) {
            event.channel.sendMessage("You do not have the correct role for this command").queue()
            return
        }
        queue.clear()
        event.channel.sendMessage("Queue cleared").queue()
    }

    fun startServer(event: MessageReceivedEvent) {
        val force = event.message.contentRaw.contains(" -force")
        if (!isMemberPrivileged(event) && force) return
        if (!queue.contains(event.author) || !isMemberPrivileged(event)) {
            event.channel.sendMessage("<@${event.author.id}>, you must be in the queue to start the server or have the correct role")
                .queue()
            return
        }
        if (isServerStarting) {
            event.channel.sendMessage("Server is already starting").queue()
            return
        }
        isServerStarting = true
        val gameServer = findGameServerById(httpClient, gameServerId) ?: return
        val isEmpty = gameServer.players_online == 0
        if ((queue.size == 10 || force) && isEmpty) {
            event.channel.sendMessage("Starting scrim server...").queue()
        } else {
            if (!isEmpty) event.channel
                .sendMessage("Yo, <@${event.author.id}> I can't start the server because it's not empty")
                .queue()
            else
                event.channel
                    .sendMessage("Yo, <@${event.author.id}> I can't start the server because the queue isn't full")
                    .queue()
            return
        }
        val randomPass = Math.random().toString().replace("0.", "")
        changeGameServerPassword(randomPass).use {
            log.info("Server password change response: ${it.message}")
        }
        stopGameServer().use {
            log.info("Server stop response: ${it.message}")
        }
        startGameServer().use {
            log.info("Server start response: ${it.message}")
        }
        GlobalScope.launch {
            for (x in 0..24) {
                val gameServerPing = findGameServerById(httpClient, gameServerId) ?: return@launch
                if (gameServerPing.booting) {
                    log.info("Server is still booting, waiting 5s...")
                    delay(5000)
                } else {
                    log.info("Server has booted")
                    break
                }
            }
            queue.forEach { user ->
                user.openPrivateChannel()
                    .queue { privateChannel -> privateChannel.sendMessage(generateTemplate(randomPass)).queue() }
            }
            val unconnectedUsers: ArrayList<User> = arrayListOf()
            queue.forEach { user ->
                val channel: VoiceChannel? = event.guild.voiceChannels
                    .firstOrNull { voiceChannel ->
                        voiceChannel.members.firstOrNull { member -> member.user.idLong == user.idLong } != null
                    }
                if (channel != null) {
                    event.guild.moveVoiceMember(
                        event.guild.getMember(user)!!,
                        event.guild.getVoiceChannelById(discordVoiceChannelId)
                    ).queue()
                } else {
                    unconnectedUsers.add(user)
                    user.openPrivateChannel()
                        .queue { privateChannel ->
                            privateChannel.sendMessage(
                                "Please connect to the `${event.guild.name} > ${event.guild.getVoiceChannelById(
                                    discordVoiceChannelId
                                )?.name}` voice channel"
                            ).queue()
                        }
                }
            }
            if (unconnectedUsers.size > 0) {
                val stringBuilder =
                    StringBuilder().appendln("The following queued users are not in the discord and cannot be moved to the default scrim voice channel:")
                unconnectedUsers.forEach { stringBuilder.appendln("- <@${it.id}>") }
                event.channel.sendMessage(stringBuilder.toString()).queue()
            }
            // cleanup
            queue.clear()
            isServerStarting = false
            log.info("Startup process has completed successfully")
        }

    }

    fun recoverQueue(event: MessageReceivedEvent) {
        if (!isMemberPrivileged(event)) {
            event.channel.sendMessage("You do not have the correct role for this command").queue()
            return
        }
        queue.clear()
        event.message.mentionedMembers.forEach { queue.add(it.user) }
        event.channel.sendMessage("Successfully recovered queue").queue()
        listQueue(event)
    }

    fun unknownCommand(event: MessageReceivedEvent) {
        if (!event.message.contentRaw.startsWith("!")) return
        event.channel.sendMessage("Unknown command, type `!help` for a list of valid commands").queue()
    }

    fun listCommands(event: MessageReceivedEvent) {
        val stringBuilder = StringBuilder().appendln("List of available commands:")
        Bot.Command.values()
            .filter { it != Bot.Command.UNKNOWN }
            .forEach { stringBuilder.appendln("`${it.command}` - ${it.description}") }
        event.channel.sendMessage(stringBuilder.toString()).queue()
    }

    fun manualUpload(event: MessageReceivedEvent) {
        if (autoUpload.toBoolean()) {
            event.channel.sendMessage("Auto upload is enabled, to switch to manual upload option set `dropbox.upload.auto` property to `false`")
                .queue()
            return
        }
        event.channel.sendMessage("Uploading `.dem` replay files...").queue()
        addDemosToQueue()
        uploadDemos()
    }

    private fun findGameServerById(httpClient: OkHttpClient, gameServerId: String): DatHostGameServer? {
        val serverStateRequest = Request.Builder()
            .url("https://dathost.net/api/0.1/game-servers")
            .get()
            .header("Authorization", auth64String)
            .build()
        val serverStateResponse = httpClient.newCall(serverStateRequest).execute()
        val responseBody = serverStateResponse.body?.string() ?: return null
        serverStateResponse.close()
        val gameServers = Gson().fromJson<List<DatHostGameServer>>(responseBody)
        return gameServers.first { it.id == gameServerId }
    }

    private fun changeGameServerPassword(password: String): Response {
        val formUrlEncoded: MediaType? = "application/x-www-form-urlencoded; charset=utf-8".toMediaTypeOrNull()
        val changePasswordRequest = Request.Builder()
            .url("https://dathost.net/api/0.1/game-servers/$gameServerId")
            .put("csgo_settings.password=$password".toRequestBody(formUrlEncoded))
            .header("Authorization", auth64String)
            .build()
        return httpClient.newCall(changePasswordRequest).execute()
    }

    private fun stopGameServer(): Response {
        val json: MediaType? = "application/json; charset=utf-8".toMediaTypeOrNull()
        val stopServerRequest = Request.Builder()
            .url("https://dathost.net/api/0.1/game-servers/$gameServerId/stop")
            .post("".toRequestBody(json))
            .header("Authorization", auth64String)
            .build()
        return httpClient.newCall(stopServerRequest).execute()
    }

    private fun startGameServer(): Response {
        val json: MediaType? = "application/json; charset=utf-8".toMediaTypeOrNull()
        val startServerRequest = Request.Builder()
            .url("https://dathost.net/api/0.1/game-servers/$gameServerId/start")
            .post("".toRequestBody(json))
            .header("Authorization", auth64String)
            .build()
        return httpClient.newCall(startServerRequest).execute()
    }

    private fun listGameServerFiles(): Response {
        val listGameServerFilesRequest = Request.Builder()
            .url("https://dathost.net/api/0.1/game-servers/$gameServerId/files?with_filesizes=true&hide_default_files=true")
            .get()
            .header("Authorization", auth64String)
            .build()
        return httpClient.newCall(listGameServerFilesRequest).execute()
    }

    private fun getGameServerFile(path: String): Response {
        val getFileRequest = Request.Builder()
            .url("https://dathost.net/api/0.1/game-servers/$gameServerId/files/$path")
            .get()
            .header("Authorization", auth64String)
            .build()
        return httpClient.newCall(getFileRequest).execute()
    }

    private fun generateTemplate(password: String): String {
        return "$dmTemplate\n`connect $gameServerIp;password $password`"
    }

    private fun isMemberPrivileged(event: MessageReceivedEvent): Boolean {
        return event.guild.getMembersWithRoles(event.guild.getRoleById(discordPrivilegeRoleId))
            .contains(event.member)
    }

    private fun getGameServerFiles(): List<DatHostPathsItem> {
        var rootFiles: List<DatHostPathsItem> = listOf()
        listGameServerFiles().use {
            if (!it.isSuccessful) return@use
            val responseBody = it.body?.string() ?: ""
            rootFiles = Gson().fromJson<List<DatHostPathsItem>>(responseBody) ?: listOf()
        }
        return rootFiles
    }

    private fun findGameServerFileItem(path: String): DatHostPathsItem? {
        return getGameServerFiles().firstOrNull { path == it.path }
    }

    private fun addDemosToQueue() {
        val rootFiles = getGameServerFiles()
        val filteredFiles = rootFiles.filter { it.path.endsWith(".dem") }
        val scrimFolderResult = getDropboxFolderContents(dropboxDemosFolder)
        scrimFolderResult?.let {
            val filesToUpload = filteredFiles
                .filter { file -> !it.entries.map { it.name }.contains(file.path) }
            filesToUpload.forEach {
                if (!uploadQueue.map { item -> item.path }.contains(it.path)) {
                    uploadQueue.add(UploadQueueItem(it.path, it.size))
                    log.info("Added ${it.path} to upload queue")
                }
            }
        }
    }

    private fun getDropboxFolderContents(dropboxDemosFolder: String): ListFolderResult? {
        return try {
            dropboxClient.files().listFolder(dropboxDemosFolder)
        } catch (e: NetworkIOException) {
            log.warn("Caught dropbox API network exception, if this persists it is likely there are elevated API errors")
            null
        }
    }

    private fun uploadDemos(autoUpload: Boolean = false) {
        val uploadedFiles = arrayListOf<UploadQueueItem>()
        uploadQueue.forEach {
            if (autoUpload) {
                val item = findGameServerFileItem(it.path) ?: return@forEach
                val fileSize = item.size
                if (fileSize > it.lastSize) {
                    it.lastSize = fileSize
                    return@forEach
                }
            }
            getGameServerFile(it.path).use { response ->
                response.body?.byteStream().use { `in` ->
                    val uploadPath = "$dropboxDemosFolder/${it.path}"
                    dropboxClient.files().uploadBuilder(uploadPath)
                        .uploadAndFinish(`in`)
                    uploadedFiles.add(it)
                    log.info("Uploaded ${it.path} successfully")
                }
            }
        }
        if (uploadedFiles.isNotEmpty()) {
            uploadQueue.removeAll(uploadedFiles)
            val stringBuilder = StringBuilder().appendln("New `.dem` replay files are available:")
            uploadedFiles.forEach { file ->
                val filePath = file.path
                val shareLink =
                    dropboxClient.sharing().createSharedLinkWithSettings("$dropboxDemosFolder/$filePath")
                val title = "(de_)[_1-z]*".toRegex().find(filePath)?.value ?: "Unknown Map"
                stringBuilder.appendln("$title - ${shareLink.url}")
            }
            val channel = jda.getTextChannelById(discordTextChannelId)
            channel?.sendMessage(stringBuilder)?.queue()
        }
    }

    fun enableDemoUpload() {
        dropboxClient = DbxClientV2(config, dropboxToken)
        val autoUpload = autoUpload.toBoolean()
        if (!autoUpload) {
            log.info("Started manual demo upload feature")
            return
        }
        log.info("Started auto demo upload feature")
        GlobalScope.launch {
            while (true) {
                delay(180000)
                uploadDemos(autoUpload)
            }
        }
        GlobalScope.launch {
            while (true) {
                addDemosToQueue()
                delay(30000)
            }
        }
    }

    fun enableAutoClearQueue() {
        log.info("Started autoclear queue feature")
        val hour = autoclearHour.toInt()
        GlobalScope.launch {
            while (true) {
                val currentDate = Date()
                var clearTime = Calendar.getInstance()
                clearTime.timeZone = TimeZone.getDefault()
                clearTime.set(Calendar.HOUR_OF_DAY, hour)
                clearTime.set(Calendar.MINUTE, 0)
                clearTime.set(Calendar.SECOND, 0)
                if (clearTime.time.before(currentDate)) {
                    clearTime = Calendar.getInstance()
                    clearTime.timeZone = TimeZone.getDefault()
                    clearTime.add(Calendar.DATE, 1)
                    clearTime.set(Calendar.HOUR_OF_DAY, hour)
                    clearTime.set(Calendar.MINUTE, 0)
                    clearTime.set(Calendar.SECOND, 0)
                }
                val msBetween = clearTime.time.toInstant().toEpochMilli() - currentDate.toInstant().toEpochMilli()
                if (msBetween > 1) {
                    log.info("Autoclearing in ${msBetween}ms")
                    delay(msBetween)
                    val channel = jda.getTextChannelById(discordTextChannelId) ?: return@launch
                    if (queue.size > 0) {
                        channel.sendMessage("Auto-clearing queue in 2 min").queue()
                        delay(120000)
                        queue.clear()
                        channel.sendMessage("Queue has been auto-cleared").queue()
                    }
                }
            }
        }
    }

    private inline fun <reified T> Gson.fromJson(json: String) = fromJson<T>(json, object : TypeToken<T>() {}.type)

}
