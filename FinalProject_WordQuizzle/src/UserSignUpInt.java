import java.rmi.Remote;
import java.rmi.RemoteException;

public interface UserSignUpInt extends Remote {
	boolean signUp(String name, String password) throws RemoteException;
}
