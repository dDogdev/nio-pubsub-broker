<div align="center">
  <h1>NEXUS BROKER</h1>
  <p><b>The apex of Mechanical Sympathy in Open-Source Pub/Sub.</b></p>
  <p>
    <i>"The heap is lava. GC is the enemy. Hardware is your only ally."</i>
  </p>
  <p>
    <a href="https://java.com"><img src="https://img.shields.io/badge/Java-22%2B-black?style=for-the-badge&logo=java" alt="Java 22"></a>
    <a href="LICENSE"><img src="https://img.shields.io/badge/License-MIT-black?style=for-the-badge" alt="License"></a>
    <a href="https://github.com/dDogdev/nio-pubsub-broker/pulls"><img src="https://img.shields.io/badge/PRs-Welcome-black?style=for-the-badge" alt="PRs Welcome"></a>
  </p>
</div>

---

## 🏴‍☠️ A Filosofia Nexus (Executive Summary)

Sistemas tradicionais de mensageria baseados na JVM sofrem da mesma doença crônica: a presunção de que o hardware é infinito e de que o *Garbage Collector* pode limpar a sujeira arquitetural. 

O **Nexus Broker** nasceu de uma visão open-source radical: provar que a JVM pode humilhar sistemas de alta frequência (HFT) escritos em C/C++ ou Rust, desde que as abstrações sejam removidas. Nós construímos este broker para lidar com volumes interbancários e de trading engines, unindo a base da literatura clássica de performance (como LMAX Architecture) com as inovações em modo "Preview" do Java 22.

Não há filas pesadas. Não há serialização no heap. Os lentos não sobrevivem. 

## ⚙️ Mechanical Sympathy & Bases Arquiteturais

O código-fonte do Nexus é uma carta de amor ao kernel do Linux e à CPU. Nossas bases operacionais:

1. **Project Panama (FFM API)**: 
   Eliminação total do custo de alocação no hot-path. Utilizamos Arenas globais compartilhadas e buffers off-heap (`ByteBuffer.allocateDirect()`). Seus dados viajam da placa de rede para a nossa memória e de volta para a rede com **Zero-Copy**. O *Garbage Collector* mal sabe que os pacotes existem.
   
2. **Hardware SIMD (Project Vector)**: 
   Parsing byte a byte é lento e obsoleto. O Nexus decodifica o cabeçalho binário dos pacotes (Magic Number, Flags, Topic Hash, e Payload Length) extraindo exatos 8 bytes do off-heap direto para um registrador SIMD de 64-bits. **Validação em O(1) e em 1 ciclo de clock**.

3. **LMAX Disruptor & Project Loom**:
   Nosso coração de roteamento é baseado num *RingBuffer* hiper-otimizado (Wait-Free). Nós utilizamos a anotação `@Contended` isolando ponteiros em linhas de cache L1/L2 (padding de 128 bytes) para destruir o *False Sharing*. O consumo é feito por um exército ilimitado de **Virtual Threads** (Project Loom), garantindo paralelismo brutal para fan-out de mensagens.

4. **Watchdog Implacável (Guilhotina HFT)**:
   Acreditamos no backpressure puramente mecânico. Se o `SO_SNDBUF` do socket fechar, paramos de escrever. Se o consumidor engasgar por 3 ciclos consecutivos, a conexão é *sumariamente decapitada*. O broker atende aos rápidos, e corta os lentos.

## 💻 Requisitos de Sistema

- **Sistema Operacional:** Linux (Recomendado para otimização máxima de rede/NUMA) ou Windows.
- **Java:** JDK 22+ (Obrigatório devido à dependência do Project Panama FFM, Vector API e Project Loom).
- **Maven:** 3.9+

## 🚀 Como Instalar e Executar

1. **Clone o Repositório:**
```bash
git clone https://github.com/dDogdev/nio-pubsub-broker.git
cd nio-pubsub-broker
```

2. **Compile com Preview Features:**
```bash
mvn clean compile package
```

3. **Execute com Tuning Máximo:**
O Nexus **exige** que você passe o controle das flags da JVM para ele. Para habilitar o ZGC Geracional, pré-tocar a memória e ativar os registradores de incubação, rode:
```bash
java -XX:+UseZGC -XX:+ZGenerational -XX:+AlwaysPreTouch \
     --enable-preview --add-modules jdk.incubator.vector \
     -jar target/nexus-1.0.0-SNAPSHOT.jar
```

## 📜 Especificação do Protocolo de Rede (Binário)

Para interagir com o Nexus, seus clientes TCP devem enviar frames estritos. Tudo é binário. Não use JSON.

**Header de 8 Bytes (Big-Endian):**
- `[Byte 0-1]`: Magic Number obrigatório (`0x4E 0x4D` = 'NM').
- `[Byte 2]`: Flags Bitmask (`0x01`=PUB, `0x02`=SUB, `0x04`=ACK).
- `[Byte 3]`: Topic Hash (`int8`, de 0 a 255) para lookup instantâneo.
- `[Byte 4-7]`: Payload Length (`int32`), tamanho do corpo da mensagem.

Seu pacote final é `[Header] + [Payload de N bytes]`.

## 🤝 Como Contribuir (Open-Source)

O Nexus Broker é uma fundação construída pela comunidade para a comunidade. Nós acolhemos pull requests, reportes de bugs e análises de profiling.

**Nosso processo de PR:**
1. Faça um fork do projeto e crie uma branch (`feature/seu-recurso` ou `perf/sua-otimizacao`).
2. Garanta que nenhuma alocação de objeto entre no `hot-path` (`WorkerLoop` ou `DisruptorEngine`).
3. Adicione testes se possível, e comite detalhando as melhorias de latência ou comportamento mecânico.
4. Abra o Pull Request. Nosso time de mantenedores revisará seu código focando agressivamente em performance.

### Áreas que precisam da sua ajuda:
- `C/JNI Sched Affinity`: Pinning nativo de threads do Java nas CPUs.
- `Bypass do Epoll`: Melhores mecanismos de otimização de SO para redes lentas.
- `Client SDKs`: Construção de drivers ultra-rápidos (C++, Rust, Go) implementando a spec binária do Nexus.

---
> *"A latência é a gravidade do software. O Nexus foi criado para quebrar a atmosfera."* — **echaib / dogmilian**
