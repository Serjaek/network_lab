import java.rmi.RemoteException;
import java.rmi.server.RemoteServer;
import java.util.HashMap;
import java.util.Set;

@SuppressWarnings("serial")
public class SignUpServerRMI extends RemoteServer implements UserSignUpInt {
	HashMap<String, User> usersDB;
	public SignUpServerRMI(HashMap<String, User> usersDB) {
		this.usersDB = usersDB;
	}
	
	//return false if user already registered
	public boolean signUp(String name, String password) throws RemoteException {
		//user is not already registered
		//System.out.println("ESITO signup: "+(usersDB==null));
		if(!usersDB.containsKey(name)) {
			//Server.nextID++;
			User user = new User(name, password);
			usersDB.put(name, user);
			Server.updateOrCreateDBFile();
			return true;
		} else {
			return false;
		}
	}
	
	//non più usata, permetteva di ottenere l'elenco degli utenti registrati
	public void getUtenti() throws RemoteException {
		Set<String> keys = usersDB.keySet();
		System.out.println();
		for (String userName : keys) {
			System.out.println(userName+": "+usersDB.get(userName));
		}
		System.out.println();
	}
}
