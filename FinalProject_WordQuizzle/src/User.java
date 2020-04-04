
import java.net.SocketAddress;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;

//-------------- AUXILIAR CLASS --------------
class NameAndScore implements Comparable<NameAndScore> {
	String username; int score;
	public NameAndScore(String username, int score) {
		this.username = username; this.score = score;
	}
	public int compareTo(NameAndScore other) {
		int otherScore = ((NameAndScore) other).score; 
		//descending order
		return otherScore - this.score;
	}
	public String toString() {
		return "score "+username+": "+score;
	}
}

//-------------- MAIN CLASS --------------
public class User {
	transient static int USERNAME = 16, PASSWORD = 8; //numero di caratteri
	String username;
	String password;
	ArrayList<String> friends;
	boolean online = false;
	// transient otherwise StackOverFlowError
	transient SocketChannel socketCh = null; //se utente è connesso allora socketCh != null
	transient SocketAddress UDPAddress = null;
	int userScore = 0;
	
	
	public User() {}
	
	public User(String username, String password) {
		this.username = username;
		this.password = password;
		friends = new ArrayList<String>();
	}
	
	//return false if already friends
	public boolean addFriend(String username) {
		boolean notAlreadyFriends = false;
		if(!friends.contains(username)) {
			friends.add(username);
			notAlreadyFriends = true;
		}
		return notAlreadyFriends;
	}
	
	public boolean isFriendWith(String username) {
		return friends.contains(username);
	}
	
	public String toString(){
		return "\n*** inizio utente ***\n"
				+ "username: "+username+"\n"
				+ "password: "+password+"\n"
				+ "score: "+userScore+"\n"
				+ "friends: "+friends.toString()+"\n"
				+ "socket: "+socketCh
				+ "\n*** fine utente ***\n";
	}
}

