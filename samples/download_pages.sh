#!/usr/bin/env bash
#curl "https://en.wikipedia.org/w/index.php?title=Special:Export&pages=Category:Pizzerias%0ACategory:American_pizza%0APizza_cutter%0ACategory:Frozen_pizza_brands%0AMezzaluna%0APizza_saver%0APizza-ghetti%0ALet's_Pizza%0AFile:Pizza_digital.png%0APizza_theorem%0AWorld_Pizza_Championship%0APizza%0APizza_delivery%0AFile:Gran_Canaria_Yumbo_Centre_Skansen_Special_Pizza.jpg%0APizza_farm%0APissaladière%0APizza_burek%0APizza_cheese%0ABaking_stone%0AThe_Pizza_Underground%0ANational_Pizza_Month%0ARaffaele_Esposito%0AGarlic_knot%0AHistory_of_pizza%0APizza_box%0APizza_party%0AJ._Patrick_Doyle%0ACategory:Pizza_styles%0ACategory:Pizza_varieties%0AList_of_pizza_varieties_by_country%0APizza_in_China%0APizza_by_the_slice&history=1&action=submit" -o "pages.xml"

#

mkdir -p pages

while read p; do
   curl -X POST 'https://en.wikipedia.org/w/index.php?title=Special:Export&pages='${p//" "/_}'&offset=1&limit=1000&action=submit' -o "pages/"${p//" "/_}".xml"
done <connected_pages_italy.csv