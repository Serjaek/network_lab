public class MainClass {
	// SHARED RESOURCE for circular buffer equipe
	int lastDoc = 0;
	
	public static void main(String[] args) {
		int red = 1 ,
				yellow = 1,
				white = 1;
		int i;
		
		if (args.length == 3) {
			red = Integer.parseInt(args[0]);
			yellow = Integer.parseInt(args[1]);
			white = Integer.parseInt(args[2]);
			System.out.printf("Patients Red: %d, Yellow: %d, White: %d\n", red, yellow, white);
		} else {
			System.out.println("needed more parameters, specify the number of patient: #RED #YELLOW #WHITE");
			return;
		}
		
		WardManager WMan = new WardManager();
		
		for (i=0; i< red; i++) {
			Patient p = new Patient(i, WMan, Code.RED);
			WMan.patientSet.add(p);
			p.start();
		}

		
		for (i=0; i< yellow; i++) {
			Patient p = new Patient(i, WMan, Code.YELLOW);
			WMan.patientSet.add(p);
			p.start();
		}
		/*
		for (i=0; i< white; i++) {
			Patient p = new Patient(i, WMan, Code.WHITE);
			WMan.patientSet.add(p);
			p.start();
		}
		*/
		WMan.start();
	}

}
