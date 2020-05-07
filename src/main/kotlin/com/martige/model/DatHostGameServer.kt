package com.martige.model

data class DatHostGameServer(
    val autostop: Boolean?,
    val autostop_minutes: Int?,
    val booting: Boolean,
    val confirmed: Boolean?,
    val cost_per_hour: Double?,
    val csgo_settings: CsgoSettings?,
    val custom_domain: String?,
    val default_file_locations: Any?,
    val disk_usage_bytes: Any?,
    val enable_mysql: Boolean?,
    val ftp_password: String?,
    val game: String?,
    val id: String?,
    val ip: String?,
    val location: String?,
    val max_cost_per_month: Double?,
    val month_credits: Double?,
    val month_reset_at: Int?,
    val mumble_settings: Any?,
    val mysql_password: String?,
    val mysql_username: String?,
    val name: String?,
    val on: Boolean?,
    val players_online: Int?,
    val ports: Ports?,
    val scheduled_commands: List<Any>?,
    val server_error: String?,
    val statuses: List<Status>?,
    val teamfortress2_settings: Any?,
    val teamspeak3_settings: Any?,
    val user_data: String?
)
