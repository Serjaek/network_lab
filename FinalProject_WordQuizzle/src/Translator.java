import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

public class Translator implements Runnable {
	String originalWord, //parola in lingua originale
		URL; //URL a cui chiedere la traduzione
	BlockingQueue<String[]> translatedOriginalWords; //coda di coppie <traduzione, originale>
	static int Timeout = 30; //attesa massima traduzioni, se scatta la partita è annullata
	
	public Translator(String originalWord, BlockingQueue<String[]> translatedWords, String URL) {
		this.originalWord = originalWord;
		this.translatedOriginalWords = translatedWords;
		this.URL = URL;
	}
	
	public void run() {
		String translatedWord = translateWord(URL);
		String[] pairTranslatedAndOriginal = {translatedWord, originalWord};
		translatedOriginalWords.add(pairTranslatedAndOriginal);
	}
	
	//ottiene la traduzione della parola inclusa nella URL di richiesta traduzione
	private String translateWord(String completeURL) {
		String json = getJsonFromHTTP_GET(completeURL);
		JsonObject jobj = new Gson().fromJson(json, JsonObject.class);
		String translatedWord = jobj.get("responseData").getAsJsonObject().get("translatedText").getAsString();
		return translatedWord.toLowerCase();
	}
	
	//Ottiene il json a seguito della get al sito
	private String getJsonFromHTTP_GET(String completeURL) {
		URL u;
		InputStream raw = null;
		try {
			u = new URL(completeURL);
			URLConnection uc = u.openConnection();
			raw = uc.getInputStream();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		Reader r = new InputStreamReader(raw);
		
		//legge un carattere alla volta
		int c;
		String json = new String();
		try {
			while ((c = r.read()) != -1) {
				json = json+(char) c;
			}
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		return json;
	}
	
	//crea una coda thread safe di coppie di parole <traduzione, originale>
	static BlockingQueue<String[]> translateWords(BlockingQueue<String> originalWords, String queryURL, String trailerURL) {
		int size = originalWords.size();
		BlockingQueue<String[]> translatedAndOriginalWords = new ArrayBlockingQueue<String[]>(size);
		
		//avvia un threadpool in cui ogni worker traduce una delle parole da tradurre
		ThreadPoolExecutor executor = (ThreadPoolExecutor) Executors.newCachedThreadPool();
		while (originalWords.size()>0) {
			String originalWord = originalWords.remove();
			String URL = queryURL+originalWord+trailerURL;
			executor.execute(new Translator(originalWord, translatedAndOriginalWords, URL));
		}
		executor.shutdown();
		try {
			if(!executor.awaitTermination(Timeout, TimeUnit.SECONDS)) {
				//le traduzioni non sono arrivate in tempo, partita non si può giocare
				return null;
			}
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		return translatedAndOriginalWords;
	}
	
	//genera casualmente una coda thread safe di parole da tradurre
	static BlockingQueue<String> selectWords(ArrayList<String> allWords, int nWordsToTranslate) {
		BlockingQueue<String> selectedWords = new ArrayBlockingQueue<String>(nWordsToTranslate);
		int nAllWords = allWords.size();
		
		//genera numeri casuali per nWordsToTranslate volte e mettile in selectedWords
		for(int i = 0; i < nWordsToTranslate; i++) {
			int index = (int) Math.floor(Math.random() * (nAllWords - i));
			selectedWords.add(allWords.remove(index));
		}
		return selectedWords;
	}
	
	//legge le parole dal dizionario
	static ArrayList<String> readDictionary(String dizionario) {
		ArrayList<String> words = new ArrayList<String>();
		BufferedReader br = null;
		try {
			br = new BufferedReader(new FileReader(dizionario));
		} catch (FileNotFoundException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		String st;
		try {
			while ((st = br.readLine()) != null) words.add(st.trim());
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		return words;
	}
}

