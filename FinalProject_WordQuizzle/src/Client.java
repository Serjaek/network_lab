/* Mi scuso per aver usato sia l'inglese che l'italiano, li ho usati entrambi in base al contesto. L'idea è di passare completamente all'inglese */
import java.io.*;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SocketChannel;
import java.rmi.*;
import java.rmi.registry.*;
import java.util.Arrays;

// -------------- AUXILIAR CLASS --------------

// classe di utilità per mantenere informazioni ricevute via UDP
class UDPBytesAndAddress {
	byte[] bytes; SocketAddress address;
	public UDPBytesAndAddress(byte[] bytes, SocketAddress address) {
		this.bytes = bytes;
		this.address = address;
	}
}

// -------------- MAIN CLASS --------------
public class Client {
	static String name3d = "Client"; //nome del thread principale
	static UserSignUpInt signUp_Server = null; //interfaccia servizio RMI
	static boolean exit = false; //per chiudere il client
	static SocketAddress socketServerAddressUDP = new InetSocketAddress("127.0.0.1", Server.UDP_Port); //indirizzo del server per comunicazione UDP
	static Object pendingChallenge = new Object(); //per mutua esclusione per l'accesso alle prossime due variabili
	static boolean thereIsAPendingChallenge = false; 
	static String challengerUsername = null; 
	static int secondsBeforeChallengeExpire; //time to live di una sfida, ottenuto dal server
	static int sizeUDPDatagram = 1+100; //1 per il byte richiesta, 100 è un valore arbitrario sufficiente con le attuali scelte implementative
	
	public static void main(String[] args) throws RemoteException, IOException {
		Thread.currentThread().setName(name3d);
		System.out.println(name3d+" - avvio client");		

		System.out.println("\n\n>>> Benvenuto su WordQuizzle! <<<");
		//setto lettura da tastiera
		String command = null;
		String input = null;
		String[] argomenti = null; //comprenderà il comando da eseguire e gli argomenti
		BufferedReader br = new BufferedReader(new InputStreamReader(System.in));
		
		//Al momento ci troviamo nello stato del client "anonimo"
		helpAnonymous();
		do {
			if (command != "exit") {
				System.out.println("\nInserisci comando ed eventuali argomenti: ");
				//i comandi devono essere formati da un'unica parola, non devono contenere spazi in quanto uno spazio viene interpretato come separatore tra il comando e gli argomenti
				input = br.readLine().trim();
				String delims = "[ ]";
				argomenti = input.split(delims);
				command = argomenti[0].toLowerCase();
				System.out.println();
			}
			
			switch(command) {
				case "help":
					helpAnonymous();
					break;
				
				case "signup": //registrazione al servio RMI offerto dal server
					Registry r = LocateRegistry.getRegistry(Server.RegistryPORT);
					// ottiene una copia dello stub (rappresentante dell'oggetto in locale) associato al server remoto
					try {
						signUp_Server = (UserSignUpInt) r.lookup(Server.serviceName);
					} catch (NotBoundException e) {
						e.printStackTrace();
						return;
					}
					if(checkNumberOfArgs(argomenti, 3)) {
						if (argomenti[1].length()>User.USERNAME || argomenti[2].length()>User.PASSWORD) {
							System.out.println("Error, username must be at most "+User.USERNAME+" characters and password at most "+User.PASSWORD+" characters");
						} else if(signUp_Server.signUp(argomenti[1], argomenti[2])) {
							System.out.println("registrazione riuscita");
						} else System.out.println("registrazione fallita, utente già registrato");
					}
					break;
				
				case "login": //verrà eseguito il login
					if(!performLogin(argomenti)) {
						command = "exit";
					}
					break;
								
				case "exit": //il processo client verrà terminato
					System.out.println("Client - Esco e termino...");
					br.close();
					exit = true;
					break;
				
				default : 
					System.out.println("Comando sconosciuto. Digita il comando \"help\" per la guida");
					break;
			}
		} while(!exit);
		
	}
	
	//Questo è lo stato del client "Loggato"
	//return false if want to close client
	private static boolean loggedPhase(String name, SocketChannel socketChannel) throws IOException {
		//per comunicazione via UDP, invia un primo messaggio per associare il client all'utente connesso
		DatagramChannel datagramChannel = DatagramChannel.open();
		sendUDPPacket((byte) 0, name.getBytes(), socketServerAddressUDP, datagramChannel.socket());
		
		//avvia il thread che si occuperà di gestire i messaggi UDP in ingresso
		startUDPDatagramReceiverThread(datagramChannel);
		
		boolean isNotExiting = true, endLoggedPhase = false;
		String command, input;
		String[] argomenti;
		BufferedReader br = new BufferedReader(new InputStreamReader(System.in));
		System.out.println("\n\n>>> Benvenuto "+name+"! <<<");
		helpLogged();
		do {
			System.out.println("\n"+name+", inserisci comando ed eventuali argomenti: ");
			input = br.readLine().trim();
			String delims = "[ ]";
			argomenti = input.split(delims);
			command = argomenti[0].toLowerCase();
			System.out.println();
			
			switch(command) {
				case "help":
					helpLogged();
					break;
				
				case "add": //aggiunge l'amico richiesto
					performAdd(argomenti, socketChannel);
					break;
				
				case "friends": //stampa un json di amici
					performFriends(socketChannel);
					break;
				
				case "vs": //avvia una possibile sfida
					performChallenge(name, argomenti, socketChannel);
					break;
				
				case "score": //mostra il punteggio di un utente
					performUserscore(argomenti, socketChannel);
					break;
				
				case "chart": //mostra la classifica degli amici dell'utente connesso
					performChart(socketChannel);
					break;
				
				case "exit": //chiude il client e fa il logout
					isNotExiting = false;
				
				case "logout": //fa il logout
					performLogout(socketChannel);
					endLoggedPhase = true;
					break;
				
				case "y": //accetta la sfida
				case "yes":
					if(thereIsAPendingChallenge) {
						performAcceptChallenge(name, challengerUsername, socketChannel);
						
						synchronized (pendingChallenge) {
							challengerUsername = null;
							thereIsAPendingChallenge = false;
						}
					} else {
						System.out.println("Non c'è sfida in attesa di essere accettata. Digita il comando \"help\" per la guida");
					}
					break;
				
				case "n": //rifiuta la sfida
				case "no":
					if(thereIsAPendingChallenge) {
						performDeclineChallenge(challengerUsername, socketChannel);
						
						synchronized (pendingChallenge) {
							challengerUsername = null;
							thereIsAPendingChallenge = false;
						}
					} else {
						System.out.println("Non c'è sfida in attesa di essere accettata. Digita il comando \"help\" per la guida");
					}					
					break;
				
				default : 
					System.out.println("Comando sconosciuto. Digita il comando \"help\" per la guida");
			}
		} while(!endLoggedPhase);
		return isNotExiting;
	}

	//crea e avvia un Thread Gestore messaggi UDP
	private static Thread startUDPDatagramReceiverThread(DatagramChannel datagramChannel) {
		Thread t = new Thread(new Runnable() {
			public void run() {
				while(!Thread.interrupted()) { //finchè non viene chiuso il client
					UDPBytesAndAddress udpMess = receiveUDPMessage(sizeUDPDatagram, datagramChannel);
					switch (udpMess.bytes[0]) {
						case 1: //è arrivata una sfida
							secondsBeforeChallengeExpire = Server.bytesToInt(Arrays.copyOfRange(udpMess.bytes, 1, Integer.BYTES+1));
							//mutua esclusione per settare che esiste una richiesta di partita
							synchronized (pendingChallenge) {
								challengerUsername = new String(udpMess.bytes, 1+Integer.BYTES, User.USERNAME).trim();
								thereIsAPendingChallenge = true;
							}							
							System.out.println("Sfida da "+challengerUsername+", accetti? [y/n] tra "+secondsBeforeChallengeExpire+" secondi verrà automaticamente rifiutata");
							break;
						
						default: System.out.println(Thread.currentThread().getName()+" Error, default case");
					}
				}
			}
		});
		t.setName(Thread.currentThread().getStackTrace()[1].getMethodName());
		t.start();
		return t;
	}
	
	//Rifiuta una sfida
	private static void performDeclineChallenge(String challengerUsername, SocketChannel socketChannel) {
		System.out.println("Challenge: hai rifiutato la sfida");
		
		//invio decline e nome sfidante al server
		String challengerUsernamePadded = String.format("%-"+User.USERNAME+"s", challengerUsername);
		sendTCPMessage((byte) 1, challengerUsernamePadded.getBytes(), socketChannel);
		
		//aspetto un nuovo messaggio dal server per conferma
		try {
			byte ack = receiveTCPMessage(1, socketChannel)[0];
			switch (ack) {
				case 0: //rifiuto ricevuto con successo
					break;
				
				case 9: //scaduta
					System.out.println("Challenge: la sfida con "+challengerUsername+" è scaduta");
					break;
		
				default: System.out.println("Error, default declineChallenge");
					break;
			}
			
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}
	
	//Accetto la sfida
	private static void performAcceptChallenge(String clientUsername, String challengerUsername, SocketChannel socketChannel) {
		System.out.println("Challenge: hai accettato la sfida");
		
		//invio accept e nome sfidante al server
		String challengerUsernamePadded = String.format("%-"+User.USERNAME+"s", challengerUsername);
		sendTCPMessage((byte) 0, challengerUsernamePadded.getBytes(), socketChannel);

		//aspetto un nuovo messaggio dal server per conferma
		try {
			byte ack = receiveTCPMessage(1, socketChannel)[0];
			switch (ack) {
				case 0: //accettazione ricevuta con successo 
					startMatch(socketChannel, clientUsername, challengerUsername);
					break;
				
				case 9: //scaduta
					System.out.println("Challenge: la sfida con "+challengerUsername+" è scaduta");
					break;
				
				default: System.out.println("Challenge: Error, default acceptChallenge");
					break;
			}
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	//Richiede di sfidare un amico
	private static void performChallenge(String clientUsername, String[] argomenti, SocketChannel socketChannel) throws IOException {
		if(checkNumberOfArgs(argomenti, 2)) {			
			//prepare request's data
			byte requestType = 6;
			String clientUsernamePadded = String.format("%-"+User.USERNAME+"s", clientUsername);
			String friendUsernamePadded = String.format("%-"+User.USERNAME+"s", argomenti[1]);
			boolean friendIsOnline = false;
			
			//send request
			sendTCPMessage(requestType, mergeBytes(clientUsernamePadded.getBytes(), friendUsernamePadded.getBytes()), socketChannel);
			
			//receive response
			byte ack = receiveTCPMessage(1, socketChannel)[0];
			switch (ack){
				case 0: //amico è online
					secondsBeforeChallengeExpire = Server.bytesToInt(receiveTCPMessage(Integer.BYTES, socketChannel));
					System.out.println(name3d+" Challenge: "+argomenti[1]+" è online; in attesa, per "+secondsBeforeChallengeExpire+" secondi, che accetti la sfida \nnon inserire altri comandi al momento");
					friendIsOnline = true;
					break;
					
				case 5: //non puoi sfidare te stesso
					System.out.println(name3d+" Challenge: non puoi sfidare te stesso!");
					break;
					
				case 6: //non siete amici
					System.out.println(name3d+" Challenge: tu e "+argomenti[1]+" non siete amici");
					break;
					
				case 7: //amico non è online
					System.out.println(name3d+" Challenge: "+argomenti[1]+" non è online");
					break;
					
				case 9: //non entrambi disponibili al momento
					System.out.println(name3d+" Challenge: tu e "+argomenti[1]+" non siete entrambi disponibili per giocare, in questo momento");
					break;
				
				default: 
					System.out.println(name3d+" performChallenge: fallita, caso di default");
			}
			
			if(friendIsOnline) {
				ack = receiveTCPMessage(1, socketChannel)[0];
				switch(ack) {
					case 0: //partita accettata
						
						//aspetto un nuovo messaggio con i settaggi della partita
						startMatch(socketChannel, clientUsername, argomenti[1]);
						break;
					
					case 8: //partita rifiutata
						System.out.println("Challenge: "+argomenti[1]+" ha rifiutato la partita");
						break;
					
					case 9: //partita scaduta
						System.out.println("Challenge: la partita è stata annullata poichè "+argomenti[1]+" non ha risposto in tempo");
						break;
					
					default: System.out.println(name3d+" default friendIsOnline performSendChallenge");
				}
			}
		}
	}
	
	//Inizia il match, interfaccia di gioco
	private static void startMatch(SocketChannel socketChannel, String player1, String player2) throws IOException {
		System.out.println("\n\n>>> Inizia WordQuizzle tra "+player1+" e "+player2+" <<<");
		System.out.println("> In attesa del server con i settaggi della partita <");
		
		
		byte ack = receiveTCPMessage(1, socketChannel)[0];
		switch (ack) {
			case 0: //ricevo settaggi partita
				//ottengo numero di parole e quanti byte è una parola (per la comunicazione)
				byte[] bytes;
				bytes = receiveTCPMessage(Integer.BYTES, socketChannel);
				int nWordsToTranslate = Server.bytesToInt(bytes);
				
				bytes = receiveTCPMessage(Integer.BYTES, socketChannel);
				int matchTime = Server.bytesToInt(bytes);
				
				////grandezza massima in byte di una parola da tradurre
				bytes = receiveTCPMessage(Integer.BYTES, socketChannel);
				int bytesSentWord = Server.bytesToInt(bytes);
				
				System.out.println("> Dovrai tradurre "+nWordsToTranslate+" parole dall'italiano all'inglese in "+matchTime+" secondi <");
								
				//partita pronta, countdown
				int secondsToWait = 3;
				System.out.println("> La sfida partirà tra "+secondsToWait+" secondi <");
				try {
					Thread.sleep(secondsToWait * 1000);
				} catch (InterruptedException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
				
				//se ack = 9 o 42 la partita è terminata
				ack = 0; boolean conclused = false;
				while(!conclused) {
					switch (ack) {
						case 0: //la partita non è terminata
							ack = receiveWordAndSendTranslated(socketChannel, bytesSentWord);
							break;
							
						case 33: //chiedo se l'altro ha finito
							try {
								Thread.sleep(1000); //attendo 1 secondo prima di chiedere di nuovo
							} catch (InterruptedException e) {
								// TODO Auto-generated catch block
								e.printStackTrace();
							}
							sendTCPMessage((byte) 9, null, socketChannel);
							ack = receiveTCPMessage(1, socketChannel)[0];
							break;
							
						case 9: //la partita è conclusa
							conclused = true;
							System.out.println("> Partita conclusa, in attesa dell'esito... <");
							
							//richiede di ottenere l'esito della partita
							byte whoWon = receiveTCPMessage(1, socketChannel)[0];
							switch (whoWon) {
								case 0: //pareggio
									System.out.println(">> Pareggio! <<");
									break;
									
								case 1: //vittoria
									System.out.println(">> Hai vinto! <<");
									break;
									
								case 2: //sconfitta
									System.out.println(">> Peccato, il tuo avversario "+player2+" ha vinto... <<");
									break;
									
								default: System.err.println(name3d+" startMatch esito: Error, default case");
							}
							break; 
							
						case 42: //partita annullata, l'altro si è disconnesso (lo dice alla SendTranslate)
							conclused = true;
							//handleAnnuledMatch(socketChannel);
							System.out.println("> Partita annullata, il tuo avversario si è disconnesso <");
							break;
							
						default: System.err.println(name3d+" startMatch esito: Error, default case #2");
					}
				}
				conclused = false; //non ce n'è bisogno in quanto variabile locale
				break;
			case 9:
				System.out.println(name3d+" startMatch: partita annullata per problema nel caricamento della partita");
				break;

			default: System.err.println(name3d+" startMatch: Error, default case");
		}
	}

	//Per ricevere e inviare le parole, serve anche a capire se la partita è conclusa o annullata
	private static byte receiveWordAndSendTranslated(SocketChannel socketChannel, int bytesSentWord) {
		byte ack = 0, request = 10; byte[] bytes = null;
		
		//Parte ReceiveWord
		sendTCPMessage(request, null, socketChannel);
		
		//ricevi risposta, controlla ack
		try {
			ack = receiveTCPMessage(1, socketChannel)[0];
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		switch(ack) {
			case 0: //leggo la parola
				try {
					bytes = receiveTCPMessage(bytesSentWord, socketChannel);
				} catch (IOException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
				
				//mostra parola da tradurre
				String wordToTranslate = new String(bytes).trim();
				System.out.println("> Traduci: "+wordToTranslate);
				
				//ottieni input con la parola tradotta
				BufferedReader br = new BufferedReader(new InputStreamReader(System.in));
				String inputTranslatedWord = null;
				try {
					inputTranslatedWord = br.readLine().trim();
				} catch (IOException e1) {
					// TODO Auto-generated catch block
					e1.printStackTrace();
				}
				
				//Parte SendTranslated 
				request = 11;
				String padded = String.format("%-"+bytesSentWord+"s", inputTranslatedWord);
				sendTCPMessage(request, padded.getBytes(), socketChannel);
				
				//ricevo ack su stato terminazione sfida
				try {
					ack = receiveTCPMessage(1, socketChannel)[0];
				} catch (IOException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
				if (ack == 33) 
					System.out.println("> Non ci sono altre parole da tradurre, in attesa che l'avversario finisca di giocare <");
				break;
				
			case 33: //non gestito poichè Server.sendWordToTranslate non invia 33
				System.err.println(name3d+" receiveWordToTranslate: Error, non gestito");
				
			case 9: //la partita è finita
				//lascio il resto al chiamante
				break;

			case 42: //l'altro si è disconnesso (lo dice alla receiveWord)
				//lascio il resto al chiamante
				break;
				
			default: System.out.println(name3d+" receiveWordToTranslate: Error, default case");
		}
		return ack;
	}

	//per ottenere il punteggio utente
	private static void performUserscore(String[] argomenti, SocketChannel socketChannel) throws IOException {
		if(checkNumberOfArgs(argomenti, 2)) {	
			//prepare request's data
			byte requestType = 7;
			String usernamePadded = String.format("%-"+User.USERNAME+"s", argomenti[1]);
			
			//send request
			sendTCPMessage(requestType, usernamePadded.getBytes(), socketChannel);
			
			//Ricevo risposta
			byte ack = (receiveTCPMessage(1, socketChannel))[0];
			
			switch(ack) {
				case 0:
					byte[] score = receiveTCPMessage(Integer.BYTES, socketChannel);
					System.out.println("Userscore di "+argomenti[1]+" è "+Server.bytesToInt(score));
				break;
				
				case 2: 
					System.out.println(name3d+" performUserscore: user not exist");
				break;
				
				default: 
					System.out.println(name3d+" performUserscore: Error, default case");
					System.out.println(name3d+": ack invalido è: "+ack);
			}
		}
	}
	
	//inutilizzato, serve a chiedere lo score senza aver fatto il login. Lo tengo per ricordo
	/*private static void performUserScoreUDP(String[] argomenti, DatagramChannel datagramChannel) throws IOException {
		if(checkNumberOfArgs(argomenti, 2)) {			
			//prepare request's data
			byte requestType = 7;
			String usernamePadded = String.format("%-"+User.USERNAME+"s", argomenti[1]);
			
			//send request
			sendUDPPacket(requestType, usernamePadded.getBytes(), socketServerAddressUDP, datagramChannel.socket());
			
			//receive response (user score)
			UDPBytesAndAddress udpmsg = receiveUDPMessage(1+Integer.BYTES, datagramChannel);
			if(udpmsg.bytes[0] == 0) {
				System.out.println("Userscore di "+argomenti[1]+" è "+Server.bytesToInt(removeFirstByte(udpmsg.bytes)));
			} else {
				System.out.println(name3d+" performUserScore: user not exist");
			}
		}
	}*/

	//return false se sta chiudendo il client, true se slogga
	private static boolean performLogin(String[] argomenti) throws IOException {
		boolean isNotExiting = true;
		if(checkNumberOfArgs(argomenti, 3)) {
			//apre connessione TCP
			SocketChannel socketChannel = SocketChannel.open();
			socketChannel.connect(new InetSocketAddress("127.0.0.1", Server.LoggerPORT));
			
			//prepara dati da inviare
			byte requestType = 2;
			String usernamePadded = String.format("%-"+User.USERNAME+"s", argomenti[1]);
			String passwordPadded = String.format("%-"+User.PASSWORD+"s", argomenti[2]);

			//invia richiesta
			sendTCPMessage(requestType, (usernamePadded+passwordPadded).getBytes(), socketChannel);
			
			//Ricevo risposta
			byte ack = (receiveTCPMessage(1, socketChannel))[0];
			
			switch(ack) {
				case 0:
					isNotExiting = loggedPhase(argomenti[1], socketChannel);
					if (isNotExiting) { //ha fatto logout e non exit
						helpAnonymous();
					}
				break;
				
				case 1: 
					System.out.println(name3d+" Login: user already in");
				break;
				
				case 2:
					System.out.println(name3d+" Login: user not exist");
				break;
				
				case 3:
					System.out.println(name3d+" Login: wrong password");
				break;
				
				default:
					System.out.println(name3d+" Login: Error, default case");
			}
		}
		return isNotExiting;
	}

	//Ottiene la classifica degli amici sotto forma di stringa JSON
	private static void performChart(SocketChannel socketChannel) throws IOException {
		//send request
		byte requestType = 8;
		sendTCPMessage(requestType, null, socketChannel);
		
		//receive size of JSON string
		byte[] lengthBytes = receiveTCPMessage(1+Integer.BYTES, socketChannel);
		lengthBytes = removeFirstByte(lengthBytes);
		int nextMessageLength = Server.bytesToInt(lengthBytes);
		
		//receive JSON String
		byte[] chartBytes = receiveTCPMessage(1+nextMessageLength, socketChannel);
		chartBytes = removeFirstByte(chartBytes);
		System.out.println(new String(chartBytes));
	}

	//Ottiene la lista degli amici sotto forma di stringa JSON
	private static void performFriends(SocketChannel socketChannel) throws IOException {
		//send request
		byte requestType = 5;
		sendTCPMessage(requestType, null, socketChannel);
		
		//receive size of JSON string
		byte[] lengthBytes = receiveTCPMessage(1+Integer.BYTES, socketChannel);
		lengthBytes = removeFirstByte(lengthBytes);
		int nextMessageLength = Server.bytesToInt(lengthBytes);
		
		//receive JSON String
		byte[] friendsBytes = receiveTCPMessage(1+nextMessageLength, socketChannel);
		friendsBytes = removeFirstByte(friendsBytes);
		System.out.println(new String(friendsBytes));
	}

	//permette di eseguire il logout
	private static void performLogout(SocketChannel socketChannel) throws IOException {
		//invio richiesta
		byte requestType = 3;
		sendTCPMessage(requestType, null, socketChannel);
		socketChannel.close();
		System.out.println("log out...");
	}

	//aggiunge l'utente del server agli amici di questo utente
	private static void performAdd(String[] argomenti, SocketChannel socketChannel) throws IOException {
		if(checkNumberOfArgs(argomenti, 2)) {
			//prepara dati da inviare
			byte requestType = 4;
			String usernamePadded = String.format("%-"+User.USERNAME+"s", argomenti[1]);
			
			//invia richiesta
			sendTCPMessage(requestType, usernamePadded.getBytes(), socketChannel);
			
			//Ricevo risposta
			byte ack = (receiveTCPMessage(1, socketChannel))[0];
			switch(ack) {
				case 0:
					System.out.println(name3d+" Add friend: "+argomenti[1]+" added to friends");
				break;
				
				case 2:
					System.out.println(name3d+" Add friend: user not exist");
				break;
				
				case 4:
					System.out.println(name3d+" Add friend: You are already friends");
				break;
				
				case 5:
					System.out.println(name3d+" Add friend: Cannot be friend with yourself");
				break;
				
				default:
					System.out.println(name3d+" Add friend: Error, default case");
					System.out.println(name3d+": ack invalido è: "+ack);
			}
		}
	}

	//Guida ai comandi client stato "anonimo"
	private static void helpAnonymous() {
		System.out.println("\nGuida comandi:");
		System.out.println("- help: mostra questa guida ai comandi");
		System.out.println("- login nomeUtente password: permette a l'utente nomeUtente di effettuare l'accesso");
		System.out.println("- signup nomeUtente password: registra l'utente nomeUtente (non effettua il log in automatico)");
		System.out.println("- exit: chiude il client");
	}
	
	//Guida ai comandi client stato "loggato"
	private static void helpLogged() {
		System.out.println("\nGuida comandi:");
		System.out.println("- help: mostra questa guida ai comandi");
		System.out.println("- add nomeUtente: aggiugne nomeUtente alla lista amici");
		System.out.println("- friends: restituisce un oggetto JSON che rappresenta la lista amici");
		System.out.println("- vs nomeAmico: invita un amico a giocare");
		System.out.println("- score nomeUtente: visualizza il punteggio di nomeUtente");
		System.out.println("- chart: visualizza la classifica dell'utente e dei suoi amici");
		System.out.println("- logout: torna alla modalità utente anonimo");
		System.out.println("- exit: disconnette utente e chiude il client");
	}
	
	//wrap to RMI service
	@SuppressWarnings("unused")
	private static boolean signUp(UserSignUpInt signUp_Server, String[] args) throws IOException {
		//esegui sign up col servizio RMI
		if(!signUp_Server.signUp(args[1], args[2])) {
			System.out.println("User already exists");
		} else return true;
		return false;
	}
	
	//return false se il numero di argomenti in input è scorretto (counting also command as argument)
	private static boolean checkNumberOfArgs(String[] args, int nArgs) throws IOException {
		boolean isOk = true;
		if (args.length<nArgs) {
			System.out.println(name3d+": ERRORE, pochi argomenti. Digita il comando \"help\" per la guida");
			isOk = false;
		} else if (args.length>nArgs) {
			System.out.println(name3d+": ERRORE, troppi argomenti. Digita il comando \"help\" per la guida");
			isOk = false;
		}
		return isOk;
	}
	
	// -------------- UTILITY METHOD --------------
	
	//invia pacchetti udp in maniera bloccante
	static void sendUDPPacket(byte request, byte[] bytes, SocketAddress address, DatagramSocket socket) throws IOException {		
		ByteBuffer buf;
		if (bytes != null) {
			buf = ByteBuffer.allocate(1+bytes.length);
			buf.put(request);
			buf.put(bytes);
		} else {
			buf = ByteBuffer.allocate(1); 
			buf.put(request);
		}
		buf.flip();
		
		DatagramPacket packet = new DatagramPacket(buf.array(), buf.array().length, address);
		//datagramChannel.socket().bind(new InetSocketAddress("127.0.0.1", UDP_Port));
		socket.send(packet);
	}
	
	//ottiene byte da un messaggio udp e l'indirizzo da cui l'ha ricevuto
	static UDPBytesAndAddress receiveUDPMessage(int bytesReceived, DatagramChannel datagramChannel) {
		SocketAddress address = null;
		ByteBuffer buf = ByteBuffer.allocate(bytesReceived);
		try {
			while ((address = datagramChannel.receive(buf))==null);
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		return new UDPBytesAndAddress(buf.array(), address);
	}
	
	//invia messaggi UDP in maniera bloccante
	static void sendTCPMessage(byte request, byte[] bytes, SocketChannel socketChannel) {
		ByteBuffer buf;
		if (bytes != null) {
			buf = ByteBuffer.allocate(1+bytes.length); //spazio fisso sufficiente a inviare tipoRichiesta, username e password
			//messaggio sul buffer
			buf.put(request);
			buf.put(bytes);
		} else {
			buf = ByteBuffer.allocate(1); //spazio fisso sufficiente a inviare tipoRichiesta, username e password
			//messaggio sul buffer
			buf.put(request);
		}
		//trasmette sul channel
		buf.flip();
		while (buf.hasRemaining()) {
			try {
				socketChannel.write(buf);
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
	}
	
	//length must include also 1 byte for request when needed
	static byte[] receiveTCPMessage(int length, SocketChannel socketChannel) throws IOException {
		ByteBuffer ackBuf = ByteBuffer.allocate(length);
		while(socketChannel.read(ackBuf) > 0);
		return ackBuf.array();
	}

	//utile per ottenere un byte array a cui è stato tolto il primo byte, spesso usato per trasmettere un ack
	static byte[] removeFirstByte(byte[] bytes) {
		byte[] reduced = new byte[bytes.length-1];
		//System.arraycopy(bytes, 1, reduced, 0, bytes.length - 1);
		for(int i = 0; i < reduced.length; i++) {
			reduced[i] = bytes[i+1];
		}
		return reduced;
	}

	//unisce due byte array in un unico byte array
	static byte[] mergeBytes(byte[] b1, byte[] b2) {
		if (b1 == null) return b2;
		if (b2 == null) return b1;
		byte[] merged = new byte[b1.length+b2.length]; 
		int i, j;
		for (i=0; i<b1.length; i++) {
			merged[i] = b1[i];
		}
		j = i;
		for (i=0; i<b2.length; i++) {
			merged[j+i] = b2[i];
		}
		return merged;
	}
}

