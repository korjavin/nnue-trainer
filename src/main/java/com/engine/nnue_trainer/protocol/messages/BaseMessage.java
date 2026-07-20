package com.engine.nnue_trainer.protocol.messages;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.PROPERTY,
    property = "type",
    defaultImpl = IgnoredMessage.class)
@JsonSubTypes({
  @JsonSubTypes.Type(value = WelcomeMessage.class, name = "welcome"),
  @JsonSubTypes.Type(value = BotWantedMessage.class, name = "bot_wanted"),
  @JsonSubTypes.Type(value = JoinLobbyMessage.class, name = "join_lobby"),
  @JsonSubTypes.Type(value = ChallengeReceivedMessage.class, name = "challenge_received")
})
public abstract class BaseMessage {
  private String type;

  public String getType() {
    return type;
  }

  public void setType(String type) {
    this.type = type;
  }
}
