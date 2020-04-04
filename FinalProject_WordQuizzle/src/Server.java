
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.rmi.*;
		import java.rmi.registry.*;
		import java.rmi.server.*;
		import java.util.*;
import java.util.concurrent.BlockingQueue;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
		
public class Server {
	static int UDP_Port = 9998; //usato per gestire la comunicazione UDP 
	static int sizeUDPDatagram = 1+100; //1 per il byte richiesta, 100 è un valore arbitrario sufficiente con le attuali scelte implementative
	static int RegistryPORT = 1201; //usato per il server RMI
	static int LoggerPORT = 9999; //usato per la comunicazione TCP
	static HashMap<String, User> usersDB = null; //map di utenti registrati raggiungibili tramite nome utente
	static HashMap<SocketChannel, User> socketToUser = null; //map di utenti a partire dal canale di comunicazione
	static HashMap<String, Object> locks = null; //map di lock a partire dai nomi degli utenti coinvolti in una sfida
	static PossibleChallenges possibleChallenges = null; //struttura dati 
	static String name3d = "Server";
	static String serviceName = "SIGNUP_Server";
	static String nameJsonDBFile = "DBUtenti.json";
	static boolean stopServer = false; //non usato al momento, messo a true blocca il mainLoop
	static int nextID = 0; //contatore di utenti iscritti, non usato
	static int secondsBeforeChallengeExpire = 7; //T1
	static String dizionario = "dizionario.txt"; //path al dizionario delle parole
	static String queryURL = "https://api.mymemory.translated.net/get?q="; //stringa con la query al sito di traduzione
	static String trailerUrl = "!&langpair=it|en"; //aggiugnere questa in chiusura alla URL
	static int nWordsToTranslate = 2; //K
	static int matchTime = 10; //T2
	static int bytesSentWord = 64; //grandezza massima in byte di una parola da tradurre
	static int pointsCorrectTranslation = 3; //X
	static int pointsIncorrectTranslation = -1; //Y
	static int pointsExtra = 3; //Z
	
	public static void main(String[] args) throws RemoteException, IOException {
		Thread.currentThread().setName(name3d);
		//Crea un file json database utenti solo se non esiste 
		recuperaDBUtenti();
		avviaServerRMI();
		//printUsers();
		mainLoop();
	}
	
	/*private static void printUsers(){
		Set<String> keys = usersDB.keySet();
		System.out.println();
		for (String userName : keys) {
			System.out.println(userName+": "+usersDB.get(userName));
		}
		System.out.println();
	}*/
	
	private static void mainLoop() {
		socketToUser = new HashMap<SocketChannel, User>();
		locks = new HashMap<String, Object>();
		possibleChallenges = new PossibleChallenges();
		
		//1) Creo il selettore
		Selector selector = null;
		try {
			selector = Selector.open();
		} catch (IOException e1) {
			// TODO Auto-generated catch block
			e1.printStackTrace();
		}
		//2) Registro il ServerSocketChannel e il DatagramChannel al selettore per TCP e UDP
		ServerSocketChannel serverSocketChannel = null;
		DatagramChannel datagramChannel = null;
		try {
			serverSocketChannel = ServerSocketChannel.open();
			datagramChannel = datagramChannel.open();
			//associo i channel alle porte dedicate al servizio
			serverSocketChannel.socket().bind(new InetSocketAddress(LoggerPORT));
			datagramChannel.socket().bind(new InetSocketAddress(UDP_Port));
			serverSocketChannel.configureBlocking(false);
			datagramChannel.configureBlocking(false);
			serverSocketChannel.register(selector, SelectionKey.OP_ACCEPT);
			datagramChannel.register(selector, SelectionKey.OP_READ);
		} catch (IOException e) {
			e.printStackTrace();
			return;
		}		
		
		SocketChannel socketChannel = null;
		while(!stopServer) {
		//3) Ottengo le chiavi che sono pronte (select è bloccante), all'inizio solo quella del server che farà accept
			try {
				selector.select();
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
			System.out.println("select fatta, prossima istruzione...");
			Set<SelectionKey> selectedKeys = selector.selectedKeys();
			Iterator <SelectionKey> keyIterator = selectedKeys.iterator();
		//4) Per ogni chiave controllo le sue operazioni, in teoria solo 1 per come vorrei fare al momento
			while(keyIterator.hasNext()) {
				boolean channelChiuso = false;
				SelectionKey key = (SelectionKey) keyIterator.next();
		//5) rimuovo la chiave dall'insieme delle chiavi per resettare il ready set della chiave selezionata altrimenti verrebbe nuovamente letta
				keyIterator.remove();
				
		//6) eseguo le azioni possibili sulle chiavi pronte
				if(key.isAcceptable() && !channelChiuso) {
					//accept connection
					try {
						socketChannel = ((ServerSocketChannel) key.channel()).accept();
						socketChannel.configureBlocking(false);
						socketChannel.register(selector, SelectionKey.OP_READ);
					} catch (IOException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
				}
				if(key.isReadable() && !channelChiuso){
					if (key.channel() != datagramChannel) {
						SocketChannel clientChannel = (SocketChannel) key.channel();
						
						//get the type of request
						byte requestType;
						try {
							requestType = (Client.receiveTCPMessage(1, clientChannel))[0];
							System.out.println(name3d+": requestType "+new Byte(requestType).toString());
						} catch (IOException e) {
							channelChiuso = forcedExit(key);
							break;
						}
						
						//specialization
						byte ack;
						byte[] bytes;
						switch(requestType) {
							case 0: //riceve che un certo utente ha accettato la sfida
								acceptedMatch(clientChannel);
								break;
							
							case 1: //riceve che un certo utente ha rifiutato la sfida
								refusedMatch(clientChannel);
								break;
						
							case 2: //login request
								channelChiuso = performLogin(requestType, clientChannel, key);
								break;
							
							case 3: //logout
								System.out.println(name3d+": doing a logout");
								User user = socketToUser.get(key.channel());
								logout(user);
								try {
									key.channel().close();
								} catch (IOException e) {
									// TODO Auto-generated catch block
									e.printStackTrace();
								}
								break;
							
							case 4: //add friend
								System.out.println(name3d+": adding friend");
								//get data into buffer
								try {
									bytes = Client.receiveTCPMessage(User.USERNAME, clientChannel);
								} catch (IOException e) {
									channelChiuso = forcedExit(key);
									break;
								}
								
								// add friend
								String friend = new String(bytes).trim();
								System.out.println(name3d+": adding "+friend);
								ack = performAdd(friend, clientChannel);
								
								//send ack back
								Client.sendTCPMessage(ack, null, clientChannel);
								break;
							
							case 5: //show friends (as JSON string)
								performFriends(clientChannel);
								break;
							
							case 6: //challenge
								//Client is LOGGED, I assume it because client code is in client's "logged" state
								try {
									performChallenge(clientChannel, datagramChannel);
								} catch (IOException e1) {
									// TODO Auto-generated catch block
									e1.printStackTrace();
								}
								break;
							
							case 7: //invia il punteggio dell'utente richiesto
								performUserscore(clientChannel, key);
								break;
							
							case 8: //show charts (as JSON string)
								performCharts(clientChannel);
								break;
							
							case 9: //restituisce al client se il suo sfidante ha terminato le parole
								hasTheOtherPlayerFinished(clientChannel);
								break;
							
							case 10: //send word to translate (Client.receiveWordAndSendTranslated)
								sendWordToTranslate(clientChannel);
								break;
							
							case 11: //receive word translated (Client.receiveWordAndSendTranslated)
								receiveWordTranslated(clientChannel);
								break;
								
							case 12: //termina questo lato della partita (a causa di una partita nulla)
								closeChallenge(clientChannel);
								break;
									
							default:
								System.err.println(name3d+": Error, default at mainLoop");
						}
					} else {
						//receive UDP message to set client address
						UDPBytesAndAddress udpmess = Client.receiveUDPMessage(sizeUDPDatagram, datagramChannel); 
						switch (udpmess.bytes[0]) {
							case 0: //il server memorizza l'indirizzo dell'utente per comunicazioni UDP
								String clientUsername = new String(udpmess.bytes, 1, User.USERNAME).trim();
								usersDB.get(clientUsername).UDPAddress = udpmess.address;
							break;
							
							default:
								System.out.println(name3d+" mainLoop default switch in UDP handling");
						}
					}
				} else if(key.isWritable() && !channelChiuso){
					System.out.println(name3d+": ERRORE o comunque non previsto che sia qui, isWritable");
				}
			}
			
		}
	}
	
	//richiede al server di chiudere la partita annullata
	private static void closeChallenge(SocketChannel clientChannel) {
		String clientUsername = socketToUser.get(clientChannel).username;
		Object lock = locks.get(clientUsername);
		synchronized (lock) {
			//chiudi questa partita
			possibleChallenges.concludeChallenge(clientUsername);
		}
	}
	
	//verifica che l'altro giocatore abbia finito la partita 
	private static void hasTheOtherPlayerFinished(SocketChannel clientChannel) {
		String clientUsername = socketToUser.get(clientChannel).username;
		Object lock = locks.get(clientUsername);
		byte ack; byte[] bytes = null;
		synchronized (lock) {
			//controlla terminazione sfida
			ack = possibleChallenges.hasTheOtherPlayerFinished(clientUsername);
		}
		
		switch (ack) {
			case 9: //se partita terminata prepara nel payload l'esito della partita
				byte whoWon;
				synchronized (lock) {
					whoWon = possibleChallenges.whoWon(clientUsername);
					possibleChallenges.concludeChallenge(clientUsername);
				}
				bytes = new byte [] {whoWon};
				break;
				
			case 33: //l'altro non ha ancora finito
				break;
				
			case 42: //la partita dell'altro è nulla
				synchronized (lock) {
					//rendi nulla la partita di questo player
					possibleChallenges.annulChallenge(clientUsername);
				}
				break;
	
			default:
				break;
		}
		Client.sendTCPMessage(ack, bytes, clientChannel);
	}

	//invia la prossima parola da tradurre e avvisa sullo stato della partita
	private static void sendWordToTranslate(SocketChannel clientChannel) {
		String clientUsername = socketToUser.get(clientChannel).username;
		Object lock = locks.get(clientUsername);
		String wordToTranslate = null;
		byte ack; //indica che la partita è finita
		byte[] bytes = null;
		synchronized (lock) {
			ack = possibleChallenges.canObtainWordToTranslate(clientUsername);
			if (ack == 0) {
				wordToTranslate = possibleChallenges.getWordToTranslate(clientUsername);
			}
		}
		switch (ack) {
			case 0: //prepara prossima parola
				bytes = String.format("%-"+bytesSentWord+"s", wordToTranslate).getBytes();
				break;
			
			case  9: //la partita è finita, ottieni risultato
				synchronized (lock) {
					bytes = new byte[] {possibleChallenges.whoWon(clientUsername)};
					possibleChallenges.concludeChallenge(clientUsername);
				}
				break;
				
			case 42: //la partita dell'altro è nulla
				synchronized (lock) {
					//rendi nulla la partita di questo player
					possibleChallenges.annulChallenge(clientUsername);
				}
				break;
	
			default:
		}
		Client.sendTCPMessage(ack, bytes, clientChannel);
	}

	//ricevi parola tradotta e avvisa sullo stato della partita
	private static void receiveWordTranslated(SocketChannel clientChannel) {
		String clientUsername = socketToUser.get(clientChannel).username;
		Object lock = locks.get(clientUsername);
		String wordTranslated = null;
		try {
			wordTranslated = new String(Client.receiveTCPMessage(bytesSentWord, clientChannel)).trim();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		byte ack; //indica che la partita è finita
		byte[] bytes = null;
		synchronized (lock) {
			ack = possibleChallenges.putWordTranslatedAndTestTerminationMatch(wordTranslated, clientUsername);
		}
		
		//ricevo stato della partita
		switch (ack) {
		case 0: //la partita non è ancora finita
			break;
			
		case 9: //partita conclusa
			synchronized (lock) {
				bytes = new byte[] {possibleChallenges.whoWon(clientUsername)};
				possibleChallenges.concludeChallenge(clientUsername);
			}
			break;
			
		case 33: //le parole sono finite
			break;
			
		case 42: //la partita dell'altro è nulla
			synchronized (lock) {
				//rendi nulla la partita di questo player
				possibleChallenges.annulChallenge(clientUsername);
			}
			break;
			
		default: System.err.println(name3d+" performReceiveWordTranslated: Error, default case");
		}
		//invia ack ed eventuale payload
		Client.sendTCPMessage(ack, bytes, clientChannel);
	}
	
	//gestione nel caso lo sfidato rifiuti la partita
	private static void refusedMatch(SocketChannel clientChannel) {
		//riceve nome dello sfidande di client
		byte[] challengerBytes = null;
		try {
			challengerBytes = Client.receiveTCPMessage(User.USERNAME, clientChannel);
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		String challengerUsername = new String(challengerBytes, 0, User.USERNAME).trim();
		
		//prendo la lock per gestire le possiblechallenges
		Object lock = locks.get(challengerUsername);
		synchronized (lock) {
			//controlla che la sfida non è scaduta
			if(possibleChallenges.setAcceptedStatusOfThisChallengeIfNotExpired(false, challengerUsername)) {
				//System.out.println(">> refuseGame: sfida non scaduta");
				//segnala a handlerPossibleChallenge di fermarsi
				possibleChallenges.stopHandlerChallenge(challengerUsername);
				
				//invia al challenger.perform(Send)Challenge il rifiuto dell'amico
				Client.sendTCPMessage((byte) 8, null, usersDB.get(challengerUsername).socketCh);
				
				//friend si aspetta un ack 0 se è andato tutto ok
				Client.sendTCPMessage((byte) 0, null, clientChannel);
			}
			//sfida scaduta
			else {
				//viene restituito che la sfida è scaduta
				//System.out.println(">> refuseGame: sfida scaduta");
				Client.sendTCPMessage((byte) 9, null, clientChannel);
				//il challenger lo sa tramite l'handler scadenza sfida
			}
		}
	}
	
	//gestione della partita nel caso lo sfidato accetti 
	private static void acceptedMatch(SocketChannel clientChannel) {
		//riceve nome dello sfidande di client
		byte[] challengerBytes = null;
		try {
			challengerBytes = Client.receiveTCPMessage(User.USERNAME, clientChannel);
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		String challengerUsername = new String(challengerBytes, 0, User.USERNAME).trim();
		//prendo la lock per gestire le possiblechallenges
		Object lock = locks.get(challengerUsername);
		synchronized (lock) {
			if(possibleChallenges.setAcceptedStatusOfThisChallengeIfNotExpired(true, challengerUsername)) {
				//System.out.println(">> acceptGame: sfida non scaduta");
				//segnala a handlerPossibleChallenge di fermarsi
				possibleChallenges.stopHandlerChallenge(challengerUsername);
				
				Client.sendTCPMessage((byte) 0, null, clientChannel);
				SocketChannel friendChannel = usersDB.get(challengerUsername).socketCh;
				Client.sendTCPMessage((byte) 0, null, friendChannel);
				
				//server e clients vanno in startmatch
				startMatch(clientChannel, friendChannel);
			} else {
				//System.out.println(">> acceptGame: sfida scaduta");
				//viene restituito che la sfida è scaduta
				Client.sendTCPMessage((byte) 9, null, clientChannel);
				//il challenger lo sa tramite l'handler scadenza sfida
			}
		}
	}
	
	//inizia la partita per entrambi
	private static void startMatch(SocketChannel player1Channel, SocketChannel player2Channel) {
		//legge le parole dal dizionario
		ArrayList<String> allWords = Translator.readDictionary(dizionario);
		int dictionarySize = allWords.size();
		if(dictionarySize < nWordsToTranslate) {
			nWordsToTranslate = dictionarySize;
			System.out.println("Dizionario è piccolo, si tradurranno "+dictionarySize+" parole");
		}
		
		//seleziona un tot di parole dal dizionario
		BlockingQueue<String> toTranslateWords = Translator.selectWords(allWords, nWordsToTranslate);
		//traduce e ottiene le parole con cui giocare
		BlockingQueue<String[]> translatedAndOriginalBQ = Translator.translateWords(toTranslateWords, queryURL, trailerUrl);
		
		byte ack;
		if(translatedAndOriginalBQ != null) {
			//il sito ha tradotto le parole, prepara la possibleChallenge
			String clientUsername = socketToUser.get(player2Channel).username;
			Object lock = locks.get(clientUsername);
			synchronized (lock) {
				possibleChallenges.setChallenge(clientUsername, translatedAndOriginalBQ, matchTime, nWordsToTranslate, pointsCorrectTranslation, pointsIncorrectTranslation, pointsExtra);
			}
			
			//printa le soluzioni sul server tanto per vedere le parole come andavano tradotte
			System.out.println("> inizio stampa soluzioni sfida "+socketToUser.get(player1Channel).username+" vs "+socketToUser.get(player2Channel).username);
			for (String[] pair : translatedAndOriginalBQ) System.out.println(pair[1]+" -> "+pair[0]);
			System.out.println("> fine stampa soluzioni sfida "+socketToUser.get(player1Channel).username+" vs "+socketToUser.get(player2Channel).username);
			
			ack = 0;
			//invia numero di parole, tempo partita in secondi e quanti byte è una parola
			byte[] bytes = Client.mergeBytes(Client.mergeBytes(intToBytes(nWordsToTranslate), intToBytes(matchTime)), intToBytes(bytesSentWord));
			Client.sendTCPMessage(ack, bytes, player1Channel);
			Client.sendTCPMessage(ack, bytes, player2Channel);
		} else {
			ack = 9;
			//non è stato possibile tradurre le parole
			//invia partita fallita
			Client.sendTCPMessage(ack, null, player1Channel);
			Client.sendTCPMessage(ack, null, player2Channel);
		}
	}

	//address and datagramChannel are needed to send back a response
	//clientChannel: canale di comunicazione con il client che ha richiesto una sfida
	//datagramChannel: canale non bloccante del server su cui inviare datagram UDP
	//gestisce la richiesta di sfida da parte del client
	private static void performChallenge(SocketChannel clientChannel, DatagramChannel datagramChannel) throws IOException {
		boolean challengeIsPossible = false;
		byte ack;

		byte[] names = Client.receiveTCPMessage(User.USERNAME+User.USERNAME, clientChannel);

		String clientUsername = new String(names, 0, User.USERNAME).trim();
		String friendUsername = new String(names, User.USERNAME, User.USERNAME).trim();

		//la put sovrascrive quindi chi prenderà questa lock l'avrà aggiornata
		Object lock = new Object();
		locks.put(clientUsername, lock);
		locks.put(friendUsername, lock);

		User friend = null, client = usersDB.get(clientUsername);
		
		//fai dei controlli su friend
		//controlla che l'utente non stia sfidando se stesso
		if (clientUsername.equals(friendUsername)) {
			//non può sfidare se stesso
			ack = 5;
		} else {
			//controllo che tra gli amici di client ci sia friend
			if(!client.isFriendWith(friendUsername)) {
				//non sono amici
				ack = 6;
			} else {
				//amico presente, dunque anche registrato. 
				//Controllo che amico sia online
				friend = usersDB.get(friendUsername);
				if(friend.online) {
					//friend online, avvisa friend via UDP che client vuole sfidarlo
					boolean added = false;
					synchronized (lock) {
						//aggiungi la possibile sfida
						added = possibleChallenges.addChallengeIfPossible(clientUsername, friendUsername);
					}
					if (added) {
						ack = 0;
						//invia messaggio UDP ad amico
						sendUDPMessageNonBlocking((byte) 1, Client.mergeBytes(intToBytes(secondsBeforeChallengeExpire), clientUsername.getBytes()), friend.UDPAddress, datagramChannel);
						challengeIsPossible = true;
					} else {
						//avvisa che non sono entrambi liberi
						ack = 9;
					}
				} else {
					//amico offline
					ack = 7;
				}
			}
		}
		
		//send back ack to client.perform(Send)Challenge
		if(challengeIsPossible) {
			Client.sendTCPMessage(ack, intToBytes(secondsBeforeChallengeExpire), clientChannel);
			handlerPossibleChallengeThread(clientUsername, friendUsername);
		} else {
			Client.sendTCPMessage(ack, null, clientChannel);
		}
	}
	
	//esegue il gestore possibile sfida che nel caso scada la cancella e avvisa il challenger
	private static Thread handlerPossibleChallengeThread(String challenger, String friend) {
		Thread handler = new Thread(new Runnable() {
			public void run() {
				Object lock = locks.get(challenger);
				try {
					Thread.sleep(secondsBeforeChallengeExpire * 1000);
				} catch (InterruptedException e) {
					//se l'altro utente ha interagito ricevo interrupt e smetto di eseguire, a chiuderla ci penserà il server
					synchronized (lock) {
						if(possibleChallenges.isHandlerStopped(challenger)) {
							//se la partita è stata rifiutata
							if(!possibleChallenges.challengeIsAccepted(challenger)) {
								//cancella la sfida
								possibleChallenges.cancelChallenges(challenger);
							}
						} else {
							e.printStackTrace();
						}
					}
					return;
				}
				synchronized (lock) {
					//scaduta, cancella challenge
					possibleChallenges.cancelChallenges(challenger);
					
					//invia messaggio al challenger che challenge è scaduta
					Client.sendTCPMessage((byte) 9, null, usersDB.get(challenger).socketCh);
					System.out.println("handlerPossibleChallengeThread: challenge tra "+challenger+" e "+friend+" scaduta");
				}
			}
		});
		handler.setName(Thread.currentThread().getStackTrace()[1].getMethodName());
		Object lock = locks.get(challenger);
		synchronized (lock) {
			possibleChallenges.setThreadHandler(handler, challenger);
		}
		handler.start();
		return handler;
	}
	
	//restituisce la classifica sotto forma di stringa json
	private static void performCharts(SocketChannel clientChannel) {
		System.out.println(name3d+": showing Charts");
		User user = socketToUser.get(clientChannel);
		
		//create JSON string
		Gson gson = new Gson();
		String chartToJson = gson.toJson(generateChartToJSON(user));
		
		//send size of json string
		byte[] length = intToBytes(chartToJson.getBytes().length);
		Client.sendTCPMessage((byte) 0, length, clientChannel);
		
		//send json to client
		Client.sendTCPMessage((byte) 0, chartToJson.getBytes(), clientChannel);
	}
	
	//genera la classifica in stringa json
	private static NameAndScore[] generateChartToJSON(User user) {
		ArrayList<String> friends = user.friends;
		NameAndScore[] array = new NameAndScore[friends.size()+1];
		int i = 0;
		for (String friendname : friends) {
			User friend = usersDB.get(friendname);
			array[i] = new NameAndScore(friendname, friend.userScore);
			i++;
		}
		array[i] = new NameAndScore(user.username, user.userScore);
		Arrays.sort(array);
		return array;
		
		/*System.out.println("* inizio lista non ordinata *");
		for (i = 0; i < array.length; i++) {
			System.out.print(((name_score) array[i]).toString()+"; ");
		}
		System.out.println("\n* fine lista non ordinata *");*/
	}
	
	//return false if there was a forcedExit, true ow
	//mostra il punteggio utente di un utente registrato
	private static boolean performUserscore(SocketChannel clientChannel, SelectionKey key) {
		byte[] bytes;
		System.out.println(name3d+": sending score");
		//get data into buffer
		try {
			bytes = Client.receiveTCPMessage(User.USERNAME, clientChannel);
		} catch (IOException e) {
			return !forcedExit(key);
		}
		String friendUsername = new String(bytes).trim();
		User friend;
		if((friend = usersDB.get(friendUsername))!=null) {
			Client.sendTCPMessage((byte) 0, intToBytes(friend.userScore), clientChannel);
		} else {
			Client.sendTCPMessage((byte) 2, null, clientChannel);
		}
		return true;
	}

	//mostra l'elenco degli amici 
	private static void performFriends(SocketChannel clientChannel) {
		System.out.println(name3d+": showing friend");
		User user = socketToUser.get(clientChannel);
		
		//create JSON string
		Gson gson = new Gson();
		String friendsToJson = gson.toJson(user.friends);
		
		//send size of json string
		byte[] length = intToBytes(friendsToJson.getBytes().length);
		Client.sendTCPMessage((byte) 0, length, clientChannel);
		
		//send json to client
		Client.sendTCPMessage((byte) 0, friendsToJson.getBytes(), clientChannel);
	}

	//return false if there was a forcedExit, true ow
	//esegue il login
	private static boolean performLogin(byte requestType, SocketChannel clientChannel, SelectionKey key) {
		byte[] bytes;
		System.out.println(name3d+": performing login");
		//get data into buffer
		try {
			bytes = Client.receiveTCPMessage(User.USERNAME+User.PASSWORD, clientChannel);
		} catch (IOException e) {
			return !forcedExit(key);
		}
		
		//esegui il login vero e proprio
		byte ack = logger(bytes, clientChannel);
		//buf.clear(); //serve a renderlo riutilizzabile
		
		//send ack back
		Client.sendTCPMessage(ack, null, clientChannel);
		return true;
	}

	//aggiunge un amico
	private static byte performAdd(String friend, SocketChannel clientChannel) {
		byte ack;
		User friendUser;
		if((friendUser = usersDB.get(friend)) != null) { //se effettivamente l'amico esiste
			User user = socketToUser.get(clientChannel);
			if(user.username.equals(friend)) {
				ack = 5; //amico di se stesso non consentito
			} else if(user.addFriend(friend)) {
				friendUser.addFriend(user.username);
				updateOrCreateDBFile();
				ack = 0;
			} else {
				ack = 4; //già amici
			}
		} else ack = 2; //utente non registrato
		return ack;
	}
	
	//return always true, serve nel caso di uscita forzata
	private static boolean forcedExit(SelectionKey key) {
		System.out.println(name3d+": connessione con un client interrotta bruscamente");
		User user;
		if((user = socketToUser.get(key.channel())) != null) {
			logout(user);
		}
		key.cancel();
		return true;		
	}

	//restituisce un esito alla fase di login
	private static byte logger(byte[] bytes, SocketChannel socketChannel) {
		byte ack;
		String bufToString = new String(bytes);
		String userName = bufToString.substring(0, User.USERNAME).trim();
		String password = bufToString.substring(User.USERNAME).trim();
		//System.out.println(name3d+": requestType: "+requestType+" userName: "+userName+" - Password: "+password);
		User user = (User) usersDB.get(userName);
		
		if(user != null) {
			//controllo password
			if(user.password.equals(password)) {
				if(user.socketCh == null) {
					//ok
					ack = 0;
					user.socketCh = socketChannel;
					user.online = true;
					socketToUser.put(socketChannel, user);
					System.out.println(name3d+": Utente loggato: "+user.toString());
				} else {
					//user already in
					ack = 1;
				}
			} else {
				//wrong password
				ack = 3;
			}
		} else {
			//user not registered
			ack = 2;
		}
		return ack;
	}

	//serve per il logout
	private static void logout(User user) {
		//annulla partite se ne ha una in corso
		Object lock = locks.get(user.username);
		byte ack;
		synchronized (lock) {
			ack = possibleChallenges.annulChallenge(user.username);
		}
		if (ack == 0) {
			System.out.println("Annullata partita di "+user.username);
		}
		
		user.online = false;
		user.socketCh = null;
		user.UDPAddress = null;
		socketToUser.remove(user.socketCh);
		System.out.println(name3d+": logout: "+user.username);
	}
	
	//crea il o recupera dal json database utenti
	private static void recuperaDBUtenti() {
		File file = new File(nameJsonDBFile);
		if (!file.exists()) {
			System.out.println("Creazione file database json");
			updateOrCreateDBFile();
		} else {
			System.out.println("Recupero database dal file json");
			FileReader fileReader;
			try {
				fileReader = new FileReader(nameJsonDBFile);
			} catch (FileNotFoundException e) {
				e.printStackTrace();
				return;
			}
			Gson gson = new Gson();
			java.lang.reflect.Type DBType = new TypeToken<HashMap<String,User>>(){}.getType();
			usersDB = gson.fromJson(fileReader, DBType);
		}
	}
	
	//si occupa della registrazione di un utente
	private static void avviaServerRMI() throws RemoteException {
		//System.out.println("ESITO avviaServer: "+(usersDB==null));
		SignUpServerRMI server = new SignUpServerRMI(usersDB);
		
		// creo un'istanza dell'oggetto che “rappresenta” l'oggetto remoto mediante il suo riferimento, ossia lo stub
		UserSignUpInt stub = (UserSignUpInt) UnicastRemoteObject.exportObject(server, 0);
		
		// lancia un registro RMI sull'host locale, su una porta specificata e restituisce un riferimento al registro
		LocateRegistry.createRegistry(RegistryPORT);
		Registry r = LocateRegistry.getRegistry(RegistryPORT);
		
		// Pubblicazione dello stub nel registry
		
		r.rebind(serviceName, stub);
		System.out.println(name3d+" RMI ready");
		// If any communication failures occur... 
	}
	
	//scrive il database ogni volta che ci sono modifiche agli utenti
	static void updateOrCreateDBFile() {
		File file = new File(nameJsonDBFile);
		Gson gson = new Gson();
		try {
			if(file.createNewFile()) {
				usersDB = new HashMap<String, User>();
				System.out.println(name3d + ": Creating the database json file");
				//System.out.println("ESITO createDBFile: "+(usersDB==null));
			}
			FileWriter fileWriter = new FileWriter(file);
			fileWriter.write(gson.toJson(usersDB));
			fileWriter.close();
		} catch (IOException e) {
			e.printStackTrace();
			return;
		}
	}
	
	//è stata creata poichè avendo un canale udp non bloccante non posso usare la funzione sendUDP di Client
	//request: rappresenta il tipo di richiesta, consultare la relazione del progetto
	//bytes: byte array da inviare, il payload
	//address: indirizzo del destinatario
	//channel: il datagram channel da usare per l'invio dei dati
	static void sendUDPMessageNonBlocking(byte request, byte[] bytes, SocketAddress address, DatagramChannel channel) throws IOException {		
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
		//datagramChannel.socket().bind(new InetSocketAddress("127.0.0.1", UDP_Port));
		while (channel.send(buf, address) == 0);
		buf.flip();
	}

	// -------------- UTILITY METHOD --------------
	
	static byte[] intToBytes(int number) {
		return ByteBuffer.allocate(4).putInt(number).array();
	}
	
	static int bytesToInt(byte[] bytes) {
		ByteBuffer b = ByteBuffer.allocate(4).put(bytes);
		b.flip();
		return b.getInt();
	}
}
