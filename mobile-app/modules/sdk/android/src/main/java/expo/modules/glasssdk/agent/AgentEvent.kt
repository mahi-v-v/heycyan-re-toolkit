package expo.modules.glasssdk.agent

/**
 * Event constants for agent events
 */
object AgentEventConstants {
    const val MSG_AGENT_STATE_CHANGED = 1001
    const val MSG_AGENT_PARTICIPANT_EVENT = 1002
    const val MSG_AGENT_AUDIO_TRACK_EVENT = 1003
    const val MSG_AGENT_TEXT_EVENT = 1004
    const val MSG_AGENT_DATA_EVENT = 1005
    const val MSG_AGENT_ERROR = 1006
    const val MSG_AGENT_TOOL_CALL = 1007
    const val MSG_AGENT_TOOL_RESULT = 1008
    const val MSG_AGENT_ACTIVE_SPEAKERS = 1009
    const val MSG_AGENT_TOOL_EXECUTED = 1010
    const val MSG_AGENT_RPC_METHOD_CALLED = 1011
}

/**
 * Agent event message for EventBus
 */
data class AgentEventMsg(
    val cmd: Int,
    val n1: Int = 0,
    val n2: Int = 0,
    val n3: Int = 0,
    val s1: String? = null,
    val s2: String? = null,
    val s3: String? = null,
    val s4: String? = null
)
