import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class WardManager extends Thread {
	BlockingQueue<Patient> whites  = new LinkedBlockingQueue<Patient>();
	BlockingQueue<Patient> yellows = new LinkedBlockingQueue<Patient>();
	BlockingQueue<Patient> reds = new LinkedBlockingQueue<Patient>();
	BlockingQueue<Patient> patientSet = new LinkedBlockingQueue<Patient>();
	int nDocs = 10;
	List<Doctor> equipe = new ArrayList<Doctor>(nDocs);
	boolean stopService = false;
	
	public WardManager () {
		for(int i = 0; i < nDocs; i++) {
			equipe.add(new Doctor(i+1));
		}
		this.setName("WardManager");
	}
	
	public void run() {
		signalWhenFinished(this);
		while (!stopService) {
			if (!reds.isEmpty()) {
				handlerRedPatient();
			} else if (!yellows.isEmpty()) { 
					handlerYellowPatient();
			} else if(!whites.isEmpty()) {
					handlerWhitePatient();
			}
		}
	}

	private void handlerRedPatient() {
		Patient p = reds.element();
		synchronized (p) {
			p.itsTurn = true; 
			p.notifyAll(); //stop waitUntilItsTurn
			// need to wait red patient finish before continue
			//*** POTREI FARE UNA CODA DEI ROSSI COME HO FATTO PER I GIALLI
			try {
				p.wait(); // notify from isRed
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}
		reds.remove(p);
	}

	private void handlerYellowPatient() {
		Iterator<Patient> patientIt = yellows.iterator();
		while(patientIt.hasNext()) {
			Patient p = patientIt.next();
			synchronized (p) {
				p.itsTurn = true;
				p.notifyAll(); //stop waitUntilItsTurn
			}
			yellows.remove(p);
		}
	}
	
	private void handlerWhitePatient() {
		Iterator<Patient> patientIt = whites.iterator();
		while(patientIt.hasNext()) {
			Patient p = patientIt.next();
			p.itsTurn = true;
			//p.interrupt();
			whites.remove(p);
		}
	}
	
	//this method is thread safe because blockingQueue are used
	public void addPatient(Patient p, Thread t) {
		switch (p.code) {
		case WHITE:
			whites.add(p);
			//System.out.println(this.getName()+" - white list: "+whites);
			break;
		case YELLOW:
			yellows.add(p);
			//System.out.println(this.getName()+" - yellow list: "+yellows);
			break;
		case RED:
			reds.add(p);
			//System.out.println(this.getName()+" - red list: "+reds);
			break;
		default:
			break;
		}
	}
	
	
	private void signalWhenFinished(Thread WMan) {
		new Thread(new Runnable() {
			public void run() {
				for (Patient p : patientSet) {
					try {
						p.join();
					} catch (InterruptedException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
				}
				stopService = true;
			}
		}, "ThreadEndSignaler").start();
	}
}
