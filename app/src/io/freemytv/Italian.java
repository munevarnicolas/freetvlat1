package io.freemytv;

/** Italian copy for text drawn directly by the custom UI. Source keys are the existing English copy. */
final class Italian {
    private Italian() {}

    static String text(String english) {
        switch (english) {
            case "Live TV": return "TV in diretta";
            case "All Apps": return "Tutte le app";
            case "Settings": return "Impostazioni";
            case "Home Settings": return "Impostazioni Home";
            case "TV Settings": return "Impostazioni TV";
            case "HDMI Inputs": return "Ingressi HDMI";
            case "Inputs": return "Ingressi";
            case "Shortcuts": return "Scorciatoie";
            case "Weather": return "Meteo";
            case "No weather data right now.": return "Nessun dato meteo al momento.";
            case "Sunrise ": return "Alba ";
            case "Sunset ": return "Tramonto ";
            case "Crypto": return "Cripto";
            case "24h change": return "variazione in 24 h";
            case "For You": return "Per te";
            case "Couldn't open ": return "Impossibile aprire ";
            case " of ": return " di ";
            case "← → to move · OK to drop · Back to cancel": return "← → per spostare · OK per rilasciare · Indietro per annullare";
            case "Moving ": return "Spostamento di ";
            case ", position ": return ", posizione ";
            case ", tab ": return ", scheda ";
            case " of 3": return " di 3";
            case "Dollar": return "Dollaro";
            case "Series": return "Serie TV";
            case "prices from ": return "quotazioni di ";
            case "Starting…": return "Avvio…";
            case "Original": return "Originale";
            case "Home Screen, ": return "Schermata Home, ";
            case "Screensaver, preview": return "Salvaschermo, anteprima";
            case "no ads, with the new screensaver": return "senza pubblicità, con il nuovo salvaschermo";
            case "the TV's factory home screen": return "la schermata Home di fabbrica della TV";
            case ". In use": return ". In uso";
            case " of 2": return " di 2";
            case "Your TV's home screen and screensaver": return "Schermata Home e salvaschermo della TV";
            case "Home Screen": return "Schermata Home";
            case "Screensaver": return "Salvaschermo";
            case "Preview": return "Anteprima";
            case "On the TV: tick “Always allow” and press OK.": return "Sulla TV: spunta “Consenti sempre” e premi OK.";
            case "RECOMMENDATIONS": return "CONSIGLI";
            case "No TMDB key: see the README (config.properties).": return "Nessuna chiave TMDB: vedi il README (config.properties).";
            case "Error: ": return "Errore: ";
            case "Fetching titles…": return "Caricamento dei titoli…";
            case "This product uses the TMDB API but is not endorsed or certified by TMDB.": return "Usa l'API di TMDB, senza approvazione né certificazione di TMDB.";
            case "COMPARISON": return "CONFRONTO";
            case "Home button": return "Tasto Home";
            case "Free memory": return "Memoria libera";
            case "Home screen memory": return "Memoria della schermata Home";
            case "NOTICES": return "AVVISI";
            case "None yet.": return "Ancora nessuno.";
            case "No ads, with the new screensaver": return "Senza pubblicità, con il nuovo salvaschermo";
            case "The TV's factory home screen": return "La schermata Home di fabbrica della TV";
            case "Switching takes a few seconds; the screen blinks while it measures.": return "Il cambio richiede qualche secondo; lo schermo lampeggia durante le misurazioni.";
            case "now": return "ora";
            case "Measuring the original home screen…": return "Misurazione della schermata Home originale…";
            case "Switching the home screen and screensaver…": return "Cambio di schermata Home e salvaschermo…";
            case "Android didn't accept the new home screen": return "Android non ha accettato la nuova schermata Home";
            case "Turning off apps (": return "Disattivazione delle app (";
            case "Measuring Free My TV…": return "Misurazione di Free My TV…";
            case "Couldn't turn on Free My TV: ": return "Impossibile attivare Free My TV: ";
            case "Turning the apps back on…": return "Riattivazione delle app…";
            case "Turning apps back on (": return "Riattivazione delle app (";
            case "Restoring the original home screen…": return "Ripristino della schermata Home originale…";
            case "Original mode: the factory home screen, screensaver and apps are back.": return "Modalità originale: schermata Home, salvaschermo e app di fabbrica sono tornati.";
            case "the original home screen": return "la schermata Home originale";
            case "Original mode, but I couldn't turn back on: ": return "Modalità originale, ma non sono riuscito a riattivare: ";
            case "Couldn't go back to the original: ": return "Impossibile tornare all'originale: ";
            case "Free My TV's home screen crashed 3 times in a row; I switched back to the original one.": return "La schermata Home di Free My TV si è bloccata 3 volte di seguito; sono tornato a quella originale.";
            case "the home screen": return "la schermata Home";
            case "the screensaver": return "il salvaschermo";
            case "Couldn't check Free My TV: ": return "Impossibile controllare Free My TV: ";
            case "The TV update turned back on ": return "L'aggiornamento della TV ha riattivato ";
            case "Turned off what was missing: ": return "Disattivato ciò che mancava: ";
            case "The TV was updated; Free My TV is still on.": return "La TV è stata aggiornata; Free My TV è ancora attivo.";
            case "Connecting…": return "Connessione…";
            case "Accept “Allow USB debugging?” on the TV (tick “Always allow”).": return "Accetta “Consentire il debug USB?” sulla TV (spunta “Consenti sempre”).";
            case "Free My TV's home screen ran into a problem.\nGoing back to the TV's original home screen…": return "La schermata Home di Free My TV ha avuto un problema.\nRitorno alla schermata Home originale della TV…";
            case "the TV's first setup saved the Wi-Fi, and turning it off would erase those networks. "
                    + "In Settings → Network & Internet, forget the network and connect again (networks out of range don't "
                    + "show there: Claude Code can remove them from your computer), then try once more.":
                return "la configurazione iniziale della TV ha salvato il Wi-Fi, e disattivarla cancellerebbe quelle reti. "
                        + "In Impostazioni → Rete e Internet, elimina la rete e riconnettiti (le reti fuori portata non "
                        + "compaiono lì: Claude Code può rimuoverle dal computer), poi riprova.";
            // Home Settings, Shelf and Remote Buttons
            case "Language": return "Lingua";
            case "Temperature": return "Temperatura";
            case "Theme": return "Tema";
            case "Automatic": return "Automatico";
            case "Light": return "Chiaro";
            case "Dark": return "Scuro";
            case "Weather widget": return "Widget meteo";
            // What TalkBack reads on the drawer's weather card (#47)
            case ". High ": return ". Massima ";
            case ", low ": return ", minima ";
            case " Next hours: ": return " Prossime ore: ";
            case " Sunrise ": return " Alba ";
            case " Sunset ": return " Tramonto ";
            case "City": return "Città";
            case "Searching…": return "Ricerca…";
            case "Type the city's name": return "Scrivi il nome della città";
            case "Search": return "Cerca";
            case "Cancel": return "Annulla";
            case "No city with that name.": return "Nessuna città con questo nome.";
            case "Couldn't search now: ": return "Ricerca non riuscita: ";
            case "Quotes widget": return "Widget quotazioni";
            case "On": return "Attivo";
            case "Off": return "Disattivo";
            case "Shelf": return "App preferite";
            case "on the shelf, place ": return "tra le preferite, posizione ";
            case "not on the shelf": return "non tra le preferite";
            case "Remote Buttons": return "Tasti del telecomando";
            case "Netflix button": return "Tasto Netflix";
            case "YouTube button": return "Tasto YouTube";
            case "Prime Video button": return "Tasto Prime Video";
            case "TCL Channel button": return "Tasto TCL Channel";
            case "Nothing": return "Niente";
            case "Not installed": return "Non installata";
            case ", selected": return ", selezionato";
            case "OK or the arrows change the value.": return "OK o le frecce cambiano il valore.";
            case "OK picks, Back cancels.": return "OK sceglie, Indietro annulla.";
            case "Disney and Globoplay: the TV itself controls those buttons.": return "Disney e Globoplay: questi tasti li gestisce la TV stessa.";
            case "YouTube, Prime Video and Google Play: the TV itself controls those buttons.": return "YouTube, Prime Video e Google Play: questi tasti li gestisce la TV stessa.";
            case "These are the TCL remote's buttons: on this TV, the buttons work as they always did.": return "Questi sono i tasti del telecomando TCL: su questa TV funzionano come sempre.";
            default:
                if (english.startsWith("Free My TV is on: new home screen and screensaver, ")) {
                    return "Free My TV è attivo: nuova schermata Home e salvaschermo, "
                            + english.substring("Free My TV is on: new home screen and screensaver, ".length()).replace(" apps turned off.", " app disattivate.");
                }
                if (english.startsWith("On · ")) {
                    return "Attivi · " + english.substring(5).replace(" titles from the TV's apps, 3 new ones every hour.",
                            " titoli dalle app della TV, 3 nuovi ogni ora.");
                }
                if (english.startsWith("OK adds or removes (up to ")) {
                    return "OK aggiunge o toglie (fino a " + english.substring("OK adds or removes (up to ".length())
                            .replace("). ← → change the order.", "). ← → cambiano l'ordine.");
                }
                if (english.endsWith(" min ago")) return english.substring(0, english.length() - 4) + " fa";
                if (english.endsWith(" h ago")) return english.substring(0, english.length() - 4) + " fa";
                if (english.endsWith(" days ago")) return english.substring(0, english.length() - 9) + " giorni fa";
                return english;
        }
    }
}
