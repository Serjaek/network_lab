import java.util.List;
import java.util.concurrent.BlockingQueue;

public class Patient extends Thread{
	int id; //number to identify the patient, to use with 'code'
	int k; 	//number of time patient enter in ward
	WardManager WMan;
	Code code;
	boolean itsTurn = false; //flag to quit the wait
	int specNeeded = 0; // 1 <= specNeeded <= 10, 0 not used
	
	public Patient(int id, WardManager wm, Code code) {
		this.id = id;
		k = (int) Math.floor(Math.random() * 3) + 1; // 1 <= k <= 3
		WMan = wm;
		this.code = code;
		if (code == Code.YELLOW) specNeeded = (int) Math.floor(Math.random() * 10) + 1; // 1 <= specNeeded <= 10
		this.setName(code+" Patient "+id);
	}
	
	public void run() {
		System.out.println(this.getName()+" will enter in guard "+k+" times");
		switch (code) {
		case RED:
			isRed();
			break;
		case YELLOW:
			isYellow();
			break;
		case WHITE:
			isWhite();
			break;
		default:
		}
	}
	
	private void isRed() {
		for (int i = 0; i < k; i++) {
			waitUntilItsTurn();
			medicalVisitGetAllDoctors(0, WMan.equipe, i+1); //medicalVisit here
			synchronized (this) {
				notifyAll(); //to signal handlerRedPatient
			}
		}
	}
	
	private void isYellow() {
		for (int i = 0; i < k; i++) {
			waitUntilItsTurn();
			Doctor myDoc = WMan.equipe.get(specNeeded - 1);
			synchronized (myDoc) {
				myDoc.nextPatients.add(this);
				//System.out.println(myDoc.nextPatients);
				while(!WMan.reds.isEmpty() || !isTheFirst(myDoc.nextPatients)) {
					try {
						myDoc.wait(); //notify from medicalVisitGetAllDoctors, isYellow 
					} catch (InterruptedException e) {
						e.printStackTrace();
					}
				}
				myDoc.nextPatients.remove();
				medicalVisit(i+1);
				myDoc.notifyAll();
			}
		}
	}
	
	private boolean isTheFirst(BlockingQueue<Patient> list) {
		Patient p = list.element();
		boolean r = this.equals(p);
		return r;
	}

	private void isWhite() {
		for (int i = 0; i < k; i++) {
			waitUntilItsTurn();
			//*** al momento faccio una random, sarebbe più carino che venisse scelto il primo medico disponibile
			int whatDoc = (int) Math.floor(Math.random()) * 10 + 1; // 1 <= whatDoc <= 10
			Doctor aDoc = WMan.equipe.get(whatDoc - 1);
			synchronized (aDoc) {
				while(!WMan.reds.isEmpty()) {
					try {
						aDoc.wait();
					} catch (InterruptedException e) {
						e.printStackTrace();
					}
				}
				aDoc.nextPatients.remove();
				medicalVisit(i+1);
				aDoc.notifyAll();
			}
		}
	}

	private void waitUntilItsTurn() {
		// simulating the period of time before entering in guard
		long sleepTime = (long) ((Math.random() * 2 + 0.5) * 1000); // 500 <= sleepTime (in msec) <= 2500
		try {
			Thread.sleep(sleepTime);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
		WMan.addPatient(this, WMan);
		synchronized (this) {
			while (!itsTurn) {
				try {
					wait(); // WMan notify when handled
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			}
		}
		itsTurn = false;
	}

	private void medicalVisitGetAllDoctors (int index, List<Doctor> equipe, int idMedVisit) {
		int counter = equipe.size() - index;
		if (counter != 0) {
			synchronized (equipe.get(index)) {
				medicalVisitGetAllDoctors(index + 1, equipe, idMedVisit);
				equipe.get(index).notifyAll(); //to patients waiting for doctors (isYellow...)
			}
		} else {
			medicalVisit(idMedVisit);
		}
	}
	
	private void medicalVisit(int number) {
		long sleepTime = (long) ((Math.random() * 2 + 0.5) * 1000); // 500 <= sleepTime (in msec) <= 2500
		try {
			Thread.sleep(sleepTime);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
		System.out.println(this.getName()+" - "+number+"° medical visit ended");
	}
}
