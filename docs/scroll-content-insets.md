# Insets de conteúdo e teclado

`ScrollView` (`Scroll`) e `VirtualizedList` aceitam padding em `style()` ou
`contentContainerStyle(Style)`. As duas formas usam as mesmas propriedades
numéricas do protocolo e o mesmo nó; não inserem wrappers nem células.
O método `contentContainerStyle()` é um alias de `style()` e segue sua ordem
de precedência (a última chamada vence para cada propriedade).

```php
$list = VirtualizedList::of($rows, $renderRow, $key)
    ->contentContainerStyle(new Style(paddingTop: 12, paddingBottom: 48));
```

O Android aplica padding no RecyclerView com `clipToPadding=false` e no host
nativo do ScrollView. O iOS usa `UIScrollView.contentInset`. As posições do
engine são compensadas pela origem do padding, evitando aplicação dupla.
O espaço inferior faz parte da extensão rolável e continua disponível com
conteúdo curto. As chaves e os algoritmos de ancoragem não são substituídos.
No iOS, `contentSize` exclui os insets; a extensão final inclui `contentInset`.
O inset do teclado é preservado quando o padding muda.

No Android, `$insets` consulta `navigationBars` e `tappableElement` além das
barras e do recorte. Um bottom ausente recebe 48 dp (ou o fallback configurado,
se maior) somente com evidência de uma barra inferior visível de três botões.
Barra lateral, navegação por gestos, hardware e barra oculta não recebem esse
fallback sintético. Medidas reais sempre têm prioridade.

BottomSheet acompanha a altura intermediária do IME durante a animação de
fechamento, mesmo quando a visibilidade já representa o destino oculto. A
folha só retorna à posição de repouso quando essa altura chega a zero.

Toast permanece no plugin opcional `pushinbr/pam-native-toast`. A API existente
`Pam\Native\Toast\Toast::message('Pronto', icon: 'checkmark-circle')`
aceita glyph registrado em `UI\Icon` (com `iconFont`) ou asset empacotado;
`iconColor` e `iconSize` são opcionais. Android e iOS recebem `iconGlyph` /
`iconFontFamily` ou `iconSource`. Sem ícone, preserva-se o layout de texto.
O alias legado `Pam\Native\System\Toast` encaminha para o mesmo plugin.
