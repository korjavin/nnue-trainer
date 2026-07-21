sed -i 's/public static SearchResult findBestAction/public SearchResult findBestAction/' src/main/java/com/engine/nnue_trainer/search/SearchEngine.java
sed -i 's/public static SearchResult findBestActionWithTimeLimit/public SearchResult findBestActionWithTimeLimit/' src/main/java/com/engine/nnue_trainer/search/SearchEngine.java
sed -i 's/SearchEngine engine = new SearchEngine();//g' src/main/java/com/engine/nnue_trainer/search/SearchEngine.java
sed -i 's/engine\.orderActions/this.orderActions/g' src/main/java/com/engine/nnue_trainer/search/SearchEngine.java
sed -i 's/engine\.nnueModel/this.nnueModel/g' src/main/java/com/engine/nnue_trainer/search/SearchEngine.java
sed -i 's/engine\.alphaBeta/this.alphaBeta/g' src/main/java/com/engine/nnue_trainer/search/SearchEngine.java
