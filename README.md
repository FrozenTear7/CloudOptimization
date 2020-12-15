# CloudOptimization

Optymalizacja czasu wykonania usługi/aplikacji i zużycia energii na urządzeniach mobilnych 
z wykorzystaniem algorytmów uczenia maszynowego i Mobilnej Chmury Obliczeniowej testowane w środowisku symulacyjnym

## Uruchomienie na własnym telefonie

Na telefonie w głównym folderze danych (w moim przypadku zwie się `Internal shared storage` 
co jest chyba standardem utworzyć folder `Tess`, w nim utworzyć folder `tessdata`, do którego
należy skopiować dane do uczenia w języku angielskim z folderu [assets](https://github.com/FrozenTear7/CloudOptimization/tree/develop/app/src/main/assets/tessdata).

Następnie utworzyć również folder `TrainingDataLogs` w głównym folderze androidowym 
tak jak `Tess` powyżej, w którym należy utworzyć pusty plik tekstowy `trainingData.txt`.

Do tego pliku będą zapisywane dane treningowe, których następnie użyjemy do uczenia.
Logi dodają się w trybie append, więc należy go ręcznie backupować, czyścić, itd...

Po wykonaniu powyższych kroków można uruchomić aplikację, po wgraniu pliku .pdf
przetwarzanie będzie się wykonywać w nieskończonej pętli, więc w przypadku chęci zmiany pliku
należy ubić aplikację i uruchomić na nowo z innym plikiem testowym.

Nie zaleca się uruchamiać dużych plików .pdf, lepiej puścić mały 1mb plik,
a wiele razy, np. 20-50, co ustawia się w zmiennej `repeatJobs`, ze względów
na niewielką dostępną pamięć serwera chmurowego.
