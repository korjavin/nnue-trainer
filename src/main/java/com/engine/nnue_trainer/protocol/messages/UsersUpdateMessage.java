package com.engine.nnue_trainer.protocol.messages;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class UsersUpdateMessage extends BaseMessage {

  private List<User> users;

  public UsersUpdateMessage() {
    setType("users_update");
  }

  public List<User> getUsers() {
    return users;
  }

  public void setUsers(List<User> users) {
    this.users = users;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class User {
    private String id;
    private String username;
    private boolean inGame;

    @com.fasterxml.jackson.annotation.JsonProperty("userId")
    public String getId() {
      return id;
    }

    @com.fasterxml.jackson.annotation.JsonProperty("userId")
    public void setId(String id) {
      this.id = id;
    }

    public String getUsername() {
      return username;
    }

    public void setUsername(String username) {
      this.username = username;
    }

    public boolean isInGame() {
      return inGame;
    }

    public void setInGame(boolean inGame) {
      this.inGame = inGame;
    }
  }
}
