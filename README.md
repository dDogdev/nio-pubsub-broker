# echaib / dogmilian - Nexus Broker

> "The heap is lava. GC is the enemy. Mechanical Sympathy is the only way."

## Executive Summary
O Nexus Broker foi construído para humilhar arquiteturas tradicionais e bater de frente com as latências de sistemas bancários de alta frequência (HFT) e gigantes como a B3. Escrito puramente em Java 22, ele rejeita o ecossistema de objetos inflados e abraça a simbiose agressiva entre hardware e software. Não há filas baseadas em locks no hot-path, não há serialização desnecessária em heap e não há tolerância para consumidores lentos.

## Mechanical Sympathy
- **FFM API (Project Panama)**: Eliminação do custo de alocação através de Arenas compartilhadas globais e pools de memória Zero-Copy off-heap (`ByteBuffer.allocateDirect`). O bypass da JVM é total e direto à memória do Kernel.
- **SIMD Vectorization (Project Vector)**: Extração de headers de rede em *O(1)*. O decoder utiliza `ByteVector.SPECIES_64` para carregar a assinatura do frame diretamente de off-heap para um registrador SIMD de 64-bits da CPU em **1 ciclo de clock**.
- **LMAX Disruptor & Virtual Threads (Project Loom)**: O roteamento hot-path ocorre em um RingBuffer isolado (padronizado com `@Contended` para aniquilar L1/L2 False Sharing em linhas de cache de 128 bytes) e as mensagens roteadas em arrays `O(1)` usando `StampedLock` são propagadas com paralelismo massivo pelas *Virtual Threads*.
- **Slow Consumer Watchdog**: Conexões lentas são decapitadas. Se o preenchimento persistir sobre o limite mecânico do `SO_SNDBUF` do socket (fechamento de janela TCP), o cliente é sumariamente ejetado, preservando a saúde da bolsa de mensagens em vez de estrangular as Carrier Threads. O broker atende aos rápidos. Os lentos morrem.

## JVM Tuning (Mandatory)
Para ligar o Nexus e obter os benefícios do tuning ZGC, execute o binário na máquina garantindo a liberação das flags:

```bash
java -XX:+UseZGC -XX:+ZGenerational -XX:+AlwaysPreTouch \
     --enable-preview --add-modules jdk.incubator.vector \
     -jar nexus.jar
```
