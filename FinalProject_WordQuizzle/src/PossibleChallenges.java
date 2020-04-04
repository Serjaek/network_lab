
import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.BlockingQueue;

//rappresenta la partita dal punto di vista del player
class StatusChallenge {
	String username;
	int indexNextWord = 0; //è l'indice per recuperare la prossima parola da tradurre
	int score = 0; //punteggio partita 
	long startTime = -1; //serve per calcolare se il tempo è scaduto
	StatusChallenge sc_friend; //è la status challenge del suo avversario
	//boolean isConclused = false; //@ può prendere il posto di playersFinished
	boolean annulled = false; //indica che la partita è stata annullata per abbandono 
	
	public StatusChallenge (String username) {
		this.username = username;
	}
}

class Challenge {
	protected boolean accepted = false; //indica se la possible Challenge diventa accettata
	String player1, player2; //username dei due giocatori coinvolti
	Thread threadHandler; //handler che gestisce la scadenza dell'accettazione
	boolean stoppedHandler = false; //settato per indicare all'handler di fermarsi
	ArrayList<String[]> translatedAndOriginalWords; //è la lista di coppie di parole <tradotte, originali>
	StatusChallenge sc_challenger; //sfida lato sfidante
	StatusChallenge sc_friend; //sfida lato amico
	long matchTime; //durata della partita
	int nWordsToTranslate = 0; //numero di parole da tradurre
	int pointsCorrectTranslation; //punti per traduzione corretta
	int pointsIncorrectTranslation; //punti per traduzione scorretta
	int pointsExtra; //punti ottenuti in caso di non sconfitta
	int playersFinished = 0; //quando arriva a 2 vuol dire che entrambi hanno completato la loro sfida
	//boolean isConclused = false; //quando playersFinished arriva a 2 settala a true
	//boolean isAnnulled = false;
	
	public Challenge(String player1, String player2) {
		this.player1 = player1;
		this.player2 = player2;
		this.threadHandler = null;
	}
	
	//permette di recuperare lo stato della partita di uno dei due player
	StatusChallenge getStatusChallenge(String clientUsername) {
		if(sc_challenger.username.equals(clientUsername)) {
			return sc_challenger;
		} else if (sc_friend.username.equals(clientUsername)) {
			return sc_friend;
		} else {
			System.out.println("getStatusChallenge: forse c'è qualcosa di strano");
			return null;
		}
	}

	//ottieni parola da tradurre
	String getWordToTranslate(String clientUsername) {
		StatusChallenge sc = getStatusChallenge(clientUsername);
		if(sc.startTime == -1) {
			sc.startTime = System.currentTimeMillis();
		}
		if(!timeTerminated(sc)) {
			String wordToTranslate = translatedAndOriginalWords.get(sc.indexNextWord)[1];
			return wordToTranslate;
		} else {
			return null;
		}
	}

	//ritorna 0 9 33 o 42, ossia ottieni parola, partita finita, l'altro non ha finito, partita annullata
	byte putWordTranslatedAndTestTerminationMatch(String wordTranslated, String clientUsername) {
		byte ack;
		StatusChallenge sc = getStatusChallenge(clientUsername);
		if(sc.sc_friend.annulled) {
			//l'amico ha sloggato
			ack = 42;
		} else {
			if(!timeTerminated(sc)) {
				//la partita contiuna, calcola i punti
				String realTranslation = translatedAndOriginalWords.get(sc.indexNextWord)[0];
				if(wordTranslated.equals(realTranslation)) {
					sc.score = sc.score + this.pointsCorrectTranslation;
				} else {
					sc.score = sc.score + this.pointsIncorrectTranslation;
				}
				sc.indexNextWord++;
				//controlla se sono finite le parole da tradurre
				if (sc.indexNextWord >= this.nWordsToTranslate) {
					ack = 33; //ack che dice che sono finite le parole da tradurre
					playersFinished++;
					//@ dovrei mettere che se arriva a 2 setta isConclused
				} else {
					ack = 0;
				}
			} else {
				//partita terminata per tempo finito
				ack = 9;
			}
		}
		return ack;
	}

	//controlla se il tempo è finito
	private boolean timeTerminated(StatusChallenge sc) {
		if(System.currentTimeMillis() - sc.startTime >= this.matchTime) {
			playersFinished = 2;
			//@ riga sopra dinventerà isConclused = true;
			return true;
		} else return false;
	}

	//restituisci se il player in questione ha vinto, perso o pareggiato
	byte whoWon(String clientUsername) {
		byte result;
		StatusChallenge sc_client = getStatusChallenge(clientUsername);
		StatusChallenge sc_friend = sc_client.sc_friend;
		User client = Server.usersDB.get(clientUsername);
		if(sc_client.score > sc_friend.score) {
			//vittoria
			client.userScore = client.userScore + sc_client.score + this.pointsExtra; 
			result = 1;
		} else if (sc_client.score < sc_friend.score) {
			//sconfitta
			client.userScore = client.userScore + sc_client.score; 
			result = 2;
		} else {
			//pareggio
			client.userScore = client.userScore + sc_client.score + this.pointsExtra; 
			result = 0;
		}
		Server.updateOrCreateDBFile();
		return result;
	}

	//ottieni il nome dell'altro player in questa sfida
	public String theOther(String clientUsername) {
		if(clientUsername.equals(player2)) return player1;
		else return player2;
	}
}

public class PossibleChallenges {
	private HashMap<String, Challenge> possibleChallenges;
	
	public PossibleChallenges () {
		possibleChallenges = new HashMap<String, Challenge>();
	}

	//ritorna true se non esiste già una sfida o se esiste ma è annullata, inoltre cancella sfida annullata.
	private boolean canAddChallenge(String clientUsername) {
		boolean canAdd;
		Challenge challenge = possibleChallenges.get(clientUsername);
		if(challenge == null){
			canAdd = true;
		} else if(challenge.getStatusChallenge(clientUsername).annulled) {
			concludeChallenge(clientUsername);
			canAdd = true;
		} else {
			canAdd = false;
		}
		return canAdd;
	}
	
	//se possibile aggiunge una possibile sfida
	public boolean addChallengeIfPossible(String challenger, String friend){
		boolean ch1Free = false, ch2Free = false;
		
		ch1Free = canAddChallenge(challenger);
		ch2Free = canAddChallenge(friend);				
				
		if(ch1Free && ch2Free) {
			Challenge c = new Challenge(challenger, friend);
			possibleChallenges.put(challenger, c);
			possibleChallenges.put(friend, c);
			return true;
		} else {
			return false;
		}
	}
	
	//se sfida rifiutata o se scade timer cancella entrambe le sfide
	public void cancelChallenges(String challenger) {
		Challenge challenge = possibleChallenges.get(challenger);
		possibleChallenges.remove(challenge.theOther(challenger));
		possibleChallenges.remove(challenger);
	}
	
	//ATTENZIONE, fatta a fine partita, conclude solo 1 delle due sfide, va chiamata da entrambi i giocatori
	public void concludeChallenge(String challenger) {
		if((possibleChallenges.get(challenger)) != null) {
			possibleChallenges.remove(challenger);
			System.out.println("Challenge: conclusa partita lato player: "+challenger+")");
		} else {
			System.err.println(challenger+" conclusedChallenge, già precedentemente cancellata");
		}
	}
	
	//chiude la sfida dell'altro quando l'altro si disconnette prematuramente
	public void concludeOtherChallenge(String clientUsername) {
		Challenge challenge = possibleChallenges.get(clientUsername);
		String theOther = challenge.theOther(clientUsername);
		System.out.println(clientUsername+" sta chiudendo challenge di "+theOther);
		concludeChallenge(theOther);
	}
	
	//serve per vedere se la partita è stata rifiutata o accettata
	public boolean challengeIsAccepted(String challenger) {
		Challenge challenge;
		if ((challenge = possibleChallenges.get(challenger))!=null) {
			return challenge.accepted;
		} else {
			return false;
		}
	}
	
	//se la partita è non scaduta ritorna true, altrimenti false. Inoltre setta la partita come accettata o rifiutata
	public boolean setAcceptedStatusOfThisChallengeIfNotExpired(boolean accepted, String challenger) {
		Challenge challenge;
		if ((challenge = possibleChallenges.get(challenger))!=null) {
			//setta la partita come accettata e crea status challenges
			challenge.accepted = accepted;
			challenge.sc_challenger = new StatusChallenge(challenger);
			challenge.sc_friend = new StatusChallenge(challenge.theOther(challenger));
			challenge.sc_challenger.sc_friend = challenge.sc_friend;
			challenge.sc_friend.sc_friend = challenge.sc_challenger;
			return true;
		} else {
			//se non esiste vuol dire che l'handler l'ha cancellata perchè scaduta
			return false;
		}
	}
	
	//serve a impostare l'handler della scadenza accettazione
	public void setThreadHandler(Thread handler, String user) {
		Challenge challenge;
		if ((challenge = possibleChallenges.get(user)) != null) {
			challenge.threadHandler = handler;
		} else {
			System.err.println("setThreadHandler: forse c'è qualcosa di strano");
		}
	}

	//serve a indicare all'handler di fermarsi poichè la partita è stata accettata
	public void stopHandlerChallenge(String user) {
		Challenge challenge;
		if ((challenge = possibleChallenges.get(user)) != null) {
			challenge.stoppedHandler = true;
			challenge.threadHandler.interrupt();
		} else {
			System.err.println("stopHandler: forse c'è qualcosa di strano");
		}
	}
	
	//return true se handler è stato bloccato, false negli altri casi
	public boolean isHandlerStopped(String user) {
		Challenge challenge;
		if ((challenge = possibleChallenges.get(user)) != null) {
			return challenge.stoppedHandler;
		} else {
			System.err.println("isHandlerStopped: forse c'è qualcosa di strano");
			return false;
		}
	}

	//prepara le regole della partita e copia le parole da una blocking queue a una lista per comodità
	public void setChallenge(String clientUsername, BlockingQueue<String[]> translatedAndOriginalWords, int matchTime, int nWordsToTranslate, int pointsCorrectTranslation, int pointsIncorrectTranslation, int pointsExtra) {
		Challenge challenge;
		if ((challenge = possibleChallenges.get(clientUsername)) != null) {
			challenge.translatedAndOriginalWords = new ArrayList<String[]>();
			for (String[] pairTO : translatedAndOriginalWords) {
				challenge.translatedAndOriginalWords.add(pairTO);
			}
			challenge.matchTime = matchTime * 1000; //milliseconds
			challenge.nWordsToTranslate = nWordsToTranslate;
			challenge.pointsCorrectTranslation = pointsCorrectTranslation;
			challenge.pointsIncorrectTranslation = pointsIncorrectTranslation;
			challenge.pointsExtra = pointsExtra;
		} else {
			System.err.println("setTranslatedAndOriginalWords: forse c'è qualcosa di strano");
		}
	}
	
	//return 0 se si può chiedere una parola da tradurre, 9 se partita conclusa, 42 se partita annullata
	public byte canObtainWordToTranslate(String clientUsername) {
		byte ack;
		Challenge challenge = possibleChallenges.get(clientUsername);
		StatusChallenge sc = challenge.getStatusChallenge(clientUsername);
		if(sc.sc_friend.annulled) {
			//la sfida dell'amico è annullata poichè si sarà disconnesso
			ack = 42;
		} else {
			if (challenge != null) {
				String s = challenge.getWordToTranslate(clientUsername);
				if (s != null) {
					//c'è ancora almeno una parola da tradurre
					ack = 0;
				} else {
					//partita conclusa per fine tempo
					ack = 9;
				}
			} else {
				System.err.println("getWordToTranslate: forse c'è qualcosa di strano");
				ack = 127;
			}
		}
		return ack;
	}
	
	//ottieni una parola da tradurre
	public String getWordToTranslate(String clientUsername) {
		Challenge challenge;
		if ((challenge = possibleChallenges.get(clientUsername)) != null) {
			return challenge.getWordToTranslate(clientUsername);
		} else {
			System.err.println("getWordToTranslate: forse c'è qualcosa di strano");
			return null;
		}
	}

	//indica anche se la parita è finita
	public byte putWordTranslatedAndTestTerminationMatch(String wordTranslated, String clientUsername) {
		byte ack;
		Challenge challenge;
		if ((challenge = possibleChallenges.get(clientUsername)) != null) {
			ack = challenge.putWordTranslatedAndTestTerminationMatch(wordTranslated, clientUsername);
		} else {
			System.err.println("putWordTranslated: forse c'è qualcosa di strano");
			ack = -1;
		}
		return ack;
	}

	//restituisce l'esito: 0 pareggio, 1 vittoria, 2 sconfitta
	public byte whoWon(String clientUsername) {
		byte result;
		Challenge challenge;
		if ((challenge = possibleChallenges.get(clientUsername)) != null) {
			result = challenge.whoWon(clientUsername);
		} else {
			System.err.println("putWordTranslated: forse c'è qualcosa di strano");
			result = -1;
		}
		return result;
	}

	//ritorna 33 l'altro non ha finito
	//ritorna 9 se conclusa
	//ritorna 42 se l'altro si è disconnesso
	public byte hasTheOtherPlayerFinished(String myUsername) {
		Challenge challenge = possibleChallenges.get(myUsername);
		byte ack;
		if (challenge != null) {
			StatusChallenge sc = challenge.getStatusChallenge(myUsername);
			if(sc.sc_friend.annulled) {
				ack = 42;
			} else {
				if (challenge.playersFinished >= 2) {
					//@ in futuro dovrei cambiare la guardia in modo che controlli challenge.isConclused
					ack = 9;
				} else {
					//l'altro non ha ancora incrementato playersFinished poichè non ha finito le parole
					ack = 33;
				}
			}
		} else {
			System.err.println("putWordTranslated: forse c'è qualcosa di strano");
			ack = -1;
		}
		return ack;
	}

	//rendi nulla (non più valida) la challenge dell'utente in questione (che sta sloggando)
	public byte annulChallenge(String username) {
		byte ack;
		Challenge challenge = possibleChallenges.get(username);
		if (challenge != null) {
			if (challenge.accepted) {
				ack = 0;
				challenge.getStatusChallenge(username).annulled = true;
			} else ack = 9;
		} else {
			System.err.println("annulChallenge: forse c'è qualcosa di strano");
			ack = -1;
		}
		return ack;
	}
}
