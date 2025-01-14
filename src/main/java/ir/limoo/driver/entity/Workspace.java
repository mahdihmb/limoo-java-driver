package ir.limoo.driver.entity;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import ir.limoo.driver.connection.LimooRequester;
import ir.limoo.driver.exception.LimooException;
import ir.limoo.driver.util.JacksonUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Workspace {

	private static final String GET_CONVERSATIONS_URI_TEMPLATE = "workspace/items/%s/conversation/items";
	private static final String GET_CONVERSATION_URI_TEMPLATE = "workspace/items/%s/conversation/items/%s";

	@JsonProperty("id")
	private String id;

	@JsonProperty("name")
	private String key;

	@JsonProperty("worker_node")
	private WorkerNode worker;

	@JsonProperty("display_name")
	private String displayName;

	@JsonProperty("default_conversation_id")
	private String defaultConversationId;

	private final LimooRequester requester;
	private final Map<String, Conversation> conversationsMap;
	private Conversation defaultConversation;

	public Workspace(LimooRequester requester) {
		this.requester = requester;
		this.conversationsMap = new HashMap<>();
	}

	public String getKey() {
		return key;
	}

	public String getId() {
		return id;
	}

	public WorkerNode getWorker() {
		return worker;
	}

	public String getDisplayName() {
		return displayName;
	}

	public String getDefaultConversationId() {
		return defaultConversationId;
	}

	public LimooRequester getRequester() throws LimooException {
		return requester;
	}

	public Conversation getDefaultConversation() {
		if (defaultConversation == null)
			defaultConversation = new Conversation(defaultConversationId, ConversationType.PUBLIC, this);
		return defaultConversation;
	}

	public Conversation getConversationById(String conversationId) throws LimooException {
		if (conversationsMap.containsKey(conversationId))
			return conversationsMap.get(conversationId);
		String uri = String.format(GET_CONVERSATION_URI_TEMPLATE, getId(), conversationId);
		JsonNode conversationNode = requester.executeApiGet(uri, worker);
		try {
			Conversation conversation = new Conversation(this);
			JacksonUtils.deserializeIntoObject(conversationNode, conversation);
			conversationsMap.put(conversationId, conversation);
			return conversation;
		} catch (IOException e) {
			throw new LimooException(e);
		}
	}

	public List<Conversation> getConversations() throws LimooException {
		String uri = String.format(GET_CONVERSATIONS_URI_TEMPLATE, getId());
		JsonNode conversationsNode = requester.executeApiGet(uri, worker);
		try {
			ArrayNode conversationsArray = (ArrayNode) conversationsNode;
			for (JsonNode conversationNode : conversationsArray) {
				Conversation conversation = new Conversation(this);
				JacksonUtils.deserializeIntoObject(conversationNode, conversation);
				conversationsMap.put(conversation.getId(), conversation);
			}
			return new ArrayList<>(conversationsMap.values());
		} catch (IOException e) {
			throw new LimooException(e);
		}
	}
}
