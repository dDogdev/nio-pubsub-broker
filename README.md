<div align="center">
  <h1>NEXUS BROKER</h1>
  <p><b>Pub/Sub de altíssima performance com foco em Mechanical Sympathy.</b></p>
  <p>
    <a href="https://java.com"><img src="https://img.shields.io/badge/Java-22%2B-black?style=for-the-badge&logo=java" alt="Java 22"></a>
    <a href="https://github.com/dDogdev/nio-pubsub-broker/pulls"><img src="https://img.shields.io/badge/PRs-Welcome-black?style=for-the-badge" alt="PRs Welcome"></a>
  </p>
</div>

---

## 🏴‍☠️ Visão Geral

O **Nexus Broker** é um message broker open-source focado puramente em throughput e baixa latência. Sistemas tradicionais na JVM costumam sofrer com overhead de garbage collection e alocação excessiva em momentos de pico (hot-path).

A ideia aqui é aplicar princípios severos de otimização: remover filas baseadas em locks pesados, evitar o uso da heap o máximo possível e punir clientes lentos que tentam ditar o ritmo da rede. O Nexus foi construído usando as features experimentais do Java 22 para competir com soluções nativas (C++/Rust) em ambientes de trading (HFT).

## 📊 Status do Projeto & Roadmap

O Nexus encontra-se em **fase Alpha (v1.0.0-SNAPSHOT)**. O núcleo do motor está finalizado, mas o foco atual é a estabilização e a bateria de testes. Como ainda não atingimos a primeira release estável, não há binários pré-compilados na aba de **Releases** — o uso atual exige a compilação local pelo código-fonte.

- [x] Motor de rede Multi-Reactor lock-free.
- [x] Decodificação SIMD via Vector API.
- [x] RingBuffer LMAX e Virtual Threads (Loom).
- [x] Watchdog autônomo contra slow consumers.
- [x] Pipeline CI/CD estruturado.
- [ ] Bateria extensiva de testes de integração.
- [ ] Benchmarks de latência oficiais (vs Kafka/Aeron).
- [ ] Lançamento Oficial da `v1.0.0` (Binários via GitHub Releases).

## ⚙️ Arquitetura e Mechanical Sympathy

O projeto é construído sobre quatro pilares técnicos:

1. **Project Panama (FFM API) e Zero-GC Absoluto**: 
   O hot-path atinge o ápice da simpatia de hardware: **0 bytes de alocação na heap** durante o roteamento. O broker pré-aloca 64.000 buffers de memória nativa (off-heap) no boot. Quando um socket lê dados da placa de rede, o pacote TCP é copiado em bloco diretamente para esses buffers pré-alocados no RingBuffer. O Garbage Collector do Java fica literalmente inativo durante o processamento das mensagens.
   
2. **Project Vector (SIMD)**: 
   Não fazemos parsing do cabeçalho byte a byte. O decoder carrega os 8 bytes iniciais do frame diretamente em um registrador SIMD de 64-bits. A validação do Magic Number, leitura das flags e do tamanho do payload ocorrem em apenas 1 ciclo de CPU.

3. **LMAX Disruptor & Project Loom**:
   O roteamento interno usa um RingBuffer customizado e wait-free. Isolamos os ponteiros em linhas de cache L1/L2 com a anotação `@Contended` (padding de 128 bytes) para anular o impacto de False Sharing. O consumo das mensagens é despachado por Virtual Threads, garantindo paralelismo em massa para o fan-out sem exaurir recursos de hardware.

4. **Watchdog de Slow Consumers**:
   Controlamos o uso do `SO_SNDBUF` rigidamente. Se a janela TCP do cliente saturar e ele não conseguir processar as mensagens, o broker não reduz o throughput geral. Após 3 ciclos seguidos de falha de flush na memória off-heap, a conexão do cliente lento é derrubada e limpa pelo watchdog.

## 💻 Requisitos

- **SO:** Linux (Recomendado devido a otimizações de epoll e NUMA) ou Windows.
- **Java:** JDK 22 (Obrigatório devido às dependências do Project Panama FFM, Vector API e Loom).
- **Maven:** 3.9+

## 🚀 Como Rodar

1. **Clone o repositório:**
```bash
git clone https://github.com/dDogdev/nio-pubsub-broker.git
cd nio-pubsub-broker
```

2. **Compile:**
```bash
mvn clean compile package
```

3. **Suba o broker:**
Para extrair a melhor performance, é necessário usar o ZGC e habilitar os módulos vetoriais:
```bash
java -XX:+UseZGC -XX:+ZGenerational -XX:+AlwaysPreTouch \
     --enable-preview --add-modules jdk.incubator.vector \
     -jar target/nexus-1.0.0-SNAPSHOT.jar
```

## 📜 Protocolo Binário

A comunicação via TCP exige um header binário restrito de exatamente 8 bytes.

**Estrutura do Header (Big-Endian):**
- `[Byte 0-1]`: Magic Number (`0x4E 0x4D` = 'NM').
- `[Byte 2]`: Flags Bitmask (`0x01`=PUB, `0x02`=SUB, `0x04`=ACK).
- `[Byte 3]`: Topic Hash (`int8`, de 0 a 255).
- `[Byte 4-7]`: Payload Length (`int32`).

Formato final do frame de rede: `[Header (8 bytes)] + [Payload]`.

## 🤝 Contribuindo

Pull requests são sempre bem-vindos. O critério principal de aceite no core do broker é simples: **não adicione alocações ou locks pesados no hot-path de rede**.

1. Faça um fork e crie uma branch (`feature/seu-recurso` ou `perf/sua-otimizacao`).
2. Garanta que suas mudanças não adicionam contenção de threads.
3. Abra o PR com um resumo técnico das mudanças e seu impacto na latência.

**Principais áreas para colaboração:**
- Afinidade de CPU via JNI (Thread Pinning em SOs baseados em Unix).
- Construção de SDKs nativos em Rust, C++ e Go.
- Refinamentos na mitigação nativa do Linux Epoll Bug.
