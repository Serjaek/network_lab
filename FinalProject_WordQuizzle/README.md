# Word Quizzle - Project in Java
At the moment these project and documentation are realized in italian language.

Questa cartella contiene i file sorgenti e altri file necessari a far funzionare il progetto Word Quizzle sviluppato come progetto del corso di Reti e Calcolatori, parte Laboratorio.
C'è anche la relazione sul progetto intitolata "Networking_Lab_Project_Report__Word_Quizzle"

Guida all'utilizzo: 
Per lo sviluppo del progetto ho usato l'IDE Eclipse 9.19 e JavaSE-1.8. Ho inoltre
    utilizzato la libreria esterna Gson-2.8.6.jar.
    Il progetto è composto da 7 file .java (Client, PossibleChallenges,
    Server, SignUpServerRMI, Translator, User, UserSignUpInt), un dizionario di
    parole italiane "dizionario.txt" scritte una per ogni riga e fa uso della libreria
    Gson per la gestione dei Json.
    Nella cartella principale, in cui si trovano le cartelle .setting, bin e src va messo il
    dizionario; inoltre in questa cartella verrà creato il file json DBUtenti che può
    essere modificato e/o sostituito a mano (ovviamente senza corrompere la struttura del json).
    Il progetto è composto da due programmi eseguibili, Server e Client.
    Il server va eseguito una sola volta (altri processi server finché ce n'è uno in
    esecuzione lanceranno eccezioni per porta occupata) eseguendo il codice sorgente presente in Server.java
    tramite Run As > Java Application (tasto destro sul file nel progetto caricato su eclipse). Non è previsto alcun argomento in ingresso.
    I client si eseguono lanciando il codice nel file Client.java come si fa con il server e ce ne possono essere diversi in
    esecuzione simultaneamente. Per comodità è conveniente aprire più console su
    eclipse, 1 per ogni processo, e conviene bloccarle ('Pin' Console) poiché quando
    ci sono stampe viene mostrata l'ultima console che ha ricevuto un output.
