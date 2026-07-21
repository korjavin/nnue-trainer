sed -i '23,28c\
  public GameLoopHandler(MessageSender messageSender, SearchEngine searchEngine) {\
    this.messageSender = messageSender;\
    this.searchEngine = searchEngine;\
  }\
' src/main/java/com/engine/nnue_trainer/protocol/GameLoopHandler.java
