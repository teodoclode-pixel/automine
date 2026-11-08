# AutoMine (Fabric 1.21.4, mod klienta)

Dziala na zwyklych serwerach - instalujesz tylko u siebie (folder `mods`), razem z Fabric API.

## Uzycie
1. Wez **drewniana siekiere** (rozdzka): LPM na blok = pozycja 1, PPM na blok = pozycja 2.
   Mozesz tez wpisac `/kop pos1` i `/kop pos2` (blok pod toba).
2. Wez do reki narzedzie (kilof / lopata / siekiera).
3. `/kop start` - kopie obszar od gory do dolu. Kopie tylko bloki w zasiegu reki,
   wiec podchodz do obszaru.
4. `/kop stop` - konczy kopanie.

Przy 150 wytrzymalosci mod wpisuje `/repairall` i kopie dalej.
Jesli naprawa sie nie uda i zostanie 100 lub mniej - kopanie sie zatrzymuje.
Progi i inne ustawienia sa na gorze pliku `AutoMineClient.java`.

## Budowanie na GitHubie
Wrzuc zawartosc tego folderu (razem z `.github`) do repozytorium -> zakladka Actions
-> pobierz `automine-jar` z Artifacts.
