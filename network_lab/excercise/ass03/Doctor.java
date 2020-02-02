import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.locks.ReentrantLock;

public class Doctor extends ReentrantLock{
	int specialization; // 1 <= specialization <= nDocs
	BlockingQueue<Patient> nextPatients = new LinkedBlockingQueue<Patient>();
	
	public Doctor(int speciality) {
		this.specialization = speciality;
	}
	
	public void addPatient (Patient p) {
		nextPatients.add(p);
	}
}
