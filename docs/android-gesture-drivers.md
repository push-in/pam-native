# Drivers de interação no Android

As APIs abaixo são optativas. Não há execução PHP por frame. O estado visual é
aplicado na UI thread, e os contratos existentes mantêm seus valores padrão.

## Fundo pressionado

```php
Pressable::make(Text::make('Selecionar'))
    ->style(new \Pam\Native\Style(backgroundColor: $surface))
    ->pressedBackgroundColor($pressedSurface);
```

Em templates: `<Pressable :pressedBackgroundColor="$pressedSurface">`.
A cor aceita o mesmo ARGB inteiro da cor de fundo; o template também aceita hex.
Escolha os tokens correspondentes ao tema claro ou escuro. O preenchimento muda
em 100 ms ao entrar e sair do estado pressionado, preservando raio, borda e
ripple. Cancelamento, desativação, ocultação da janela e detach restauram o fundo
e cancelam a animação. Movimento reduzido aplica o destino imediatamente.
Gradientes continuam sendo desenhados acima do preenchimento de base.

## Arraste proporcional

```php
use Pam\Native\Animation\Drag;

$drag = Drag::vertical()->follow(0.92)->bounds(min: 0)
    ->snapPoints(0, '100%')->threshold(120, velocity: 900);
```

`follow()` aceita um fator finito de 0 a 1. O padrão é 1 (contrato serializado
anterior permanece idêntico). A posição é `posiçãoInicial + deslocamento * fator`,
antes dos limites e da resistência. A velocidade de soltura recebe o mesmo fator.
Snaps, limites e thresholds permanecem nas unidades do alvo. `drive()` lê a
posição resultante; `driveRaw()` lê a posição proporcional antes dos limites.
O fator zero mantém a posição inicial. O fator não suaviza nem atrasa frames.

## Progresso do teclado

```php
use Pam\Native\Animation\KeyboardProgress;
use Pam\Native\UI\View;

$progress = KeyboardProgress::make()
    ->drive('opacity', 1, 0)
    ->drive('translateY', 0, -120);

View::make(/* conteúdo */)->keyboardProgress($progress);
```

Em templates: `<View :keyboardProgress="$progress">`.
O primeiro valor corresponde ao teclado fechado (progresso 0), e o segundo ao
aberto (1). São aceitas até três propriedades distintas: `opacity`, `translateX`
e `translateY`. Opacidade fica em 0..1; translações são absolutas em dp.

Android 11+ segue a fração interpolada de `WindowInsetsAnimation`, invertendo o
sentido no fechamento e retomando o progresso corrente em interrupções. No fim,
a visibilidade real reconcilia o valor. Android 8–10 publica somente os estados
finais 0/1 pelo observador legado compartilhado; não simula frames em PHP.

Use um wrapper visual dedicado para não disputar as mesmas propriedades com
animações, gestos ou ajuste de teclado. O driver não move o layout nem fornece
altura do IME. Um observador nativo de tamanho zero por janela recebe os insets,
sem substituir callbacks de layout existentes. Ao remover a prop, o driver
restaura as propriedades anteriores; detach remove a assinatura. Views ocultas
ou em janela sem foco não recebem alterações por frame. Não há polling.

As propriedades são extensões negociadas: `PressBackgroundColor` (1008) e
`KeyboardProgress` (1009). Hosts antigos recusam o envio com erro de contrato,
em vez de ignorar silenciosamente. A implementação desta rodada é Android;
nenhuma implementação iOS foi acrescentada.

Referência: [WindowInsetsAnimation.Callback](https://developer.android.com/reference/android/view/WindowInsetsAnimation.Callback).
