<div align="center">
  <h1>NEXUS BROKER</h1>
  <p><b>Pub/Sub de Ultra-Baixa Latência, Mechanical Sympathy e Segurança Perimetral em Java Puro.</b></p>
  <p>
    <a href="https://java.com"><img src="https://img.shields.io/badge/Java-22%2B-black?style=for-the-badge&logo=java" alt="Java 22"></a>
    <a href="https://github.com/dDogdev/nio-pubsub-broker/pulls"><img src="https://img.shields.io/badge/PRs-Welcome-black?style=for-the-badge" alt="PRs Welcome"></a>
    <a href="#"><img src="https://img.shields.io/badge/Arch-Zero--GC-blue?style=for-the-badge" alt="Zero-GC"></a>
    <a href="#"><img src="https://img.shields.io/badge/Security-Zero--Trust-red?style=for-the-badge" alt="Zero-Trust"></a>
  </p>
</div>

---

## 🏴‍☠️ Visão Geral

O **Nexus Broker** é um message broker open-source focado puramente em throughput, latência de microsegundos e resiliência militar. Sistemas tradicionais na JVM costumam sofrer com overhead de garbage collection, alocação excessiva em momentos de pico (hot-path) e vulnerabilidades estruturais de negação de serviço.

A ideia aqui é aplicar princípios severos de otimização: remover filas baseadas em locks pesados, evitar o uso da heap o máximo possível, implementar decodificação vetorial via hardware e punir impiedosamente clientes lentos ou maliciosos que tentam ditar o ritmo ou exaurir os recursos da rede. O Nexus foi construído usando as features experimentais do Java 22 para competir com soluções nativas (C++/Rust) em ambientes de trading de alta frequência (HFT).

## ⚙️ Arquitetura e Mechanical Sympathy

O projeto é construído sobre cinco pilares técnicos e de engenharia agressiva:

### 1. Project Panama (FFM API) e Zero-GC Absoluto
O hot-path atinge o ápice da simpatia de hardware: **0 bytes de alocação na heap** durante o roteamento. O broker usa uma técnica de *Thread-Local Buffer Sharing* onde 1 único buffer de leitura off-heap (64KB) é compartilhado rotativamente pela Thread de I/O, erradicando a exaustão de memória (OOM). Os eventos do LMAX pré-alocam memória direta e a cópia de payloads (fan-out) é feita com *Deep Copy* otimizada. O Garbage Collector do Java fica literalmente inativo durante a chuva de mensagens.

### 2. Project Vector (SIMD) e Imunidade Zero-Day
Não fazemos parsing do cabeçalho byte a byte. O decoder carrega os 8 bytes iniciais do frame diretamente em um registrador SIMD de 64-bits (`ByteVector.SPECIES_64`). A validação do Magic Number, leitura das flags e extração de tamanho ocorrem em apenas **1 ciclo de CPU**. Além disso, o motor joga exceções seguras em frames maliciosos (payload negativo, fragmentações inválidas), prevenindo Denial of Service nos parsers.

### 3. LMAX Disruptor & Project Loom
O roteamento interno usa um RingBuffer customizado e lock-free de múltiplos produtores. Isolamos os ponteiros (Sequences) em linhas independentes de cache L1/L2 com padding de 128 bytes (`@Contended`) aniquilando o impacto de *False Sharing*. O consumo é orquestrado por Virtual Threads (Project Loom), garantindo paralelismo em massa para o fan-out sem exaurir threads nativas do Kernel, enquanto o RingBuffer aplica backpressure mecânico (`Thread.onSpinWait()`).

### 4. Zero-Trust Handshake & Segurança Perimetral
Para combater a exaustão volumétrica:
- **Zero-Trust Auth:** O primeiro pacote de um cliente conectado **deve** ser um long de 8 bytes com o `SERVER_SECRET` correto, ou ele é imediatamente decapitado.
- **Camada L4 Backpressure:** O `BossAcceptor` possui um hard-limit dinâmico. Acima de 50.000 conexões ativas, ele recusa o accept e transfere a pressão (TCP Zero Window) diretamente para o OS Socket Backlog.
- **Zombie Socket Reaper:** O `WorkerLoop` inspeciona a rede a cada 5 segundos. Conexões inativas por mais de 30 segundos sofrem "Drop" cirúrgico para evitar ataques de Slowloris.

### 5. Watchdog de Slow Consumers
Controlamos a exaustão do `SO_SNDBUF` de saída com crueldade. Se a janela TCP do cliente saturar e ele não conseguir processar a enxurrada de dados, o broker não reduz o ritmo global. Após uma série de 3 falhas críticas de flush, a conexão do cliente lento é fechada, evitando retenção excessiva na memória off-heap.

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

## 📜 Protocolo Binário (Zero-Trust)

Para minimizar latência, nós usamos um protocolo TCP binário estrito e stateful.

### Fase 1: O Handshake de Autenticação
Imediatamente após a conexão TCP ser estabelecida, o cliente **precisa** injetar a chave secreta de 64-bits (Big-Endian). Ex: `0xCAFEBABE12345678L`.
*Não existe reposta de "Sucesso". Se você errar a senha, o TCP é finalizado.*

### Fase 2: O Cabeçalho Padrão
Após a aceitação do Handshake, a conexão passa para o tráfego aberto de frames, sem overhead.
**Estrutura do Header (Big-Endian, 8 bytes estritos):**
- `[Byte 0-1]`: Magic Number (`0x4E 0x4D` = 'NM').
- `[Byte 2]`: Flags Bitmask (`0x01`=PUB, `0x02`=SUB, `0x04`=ACK).
- `[Byte 3]`: Topic Hash (`int8`, de 0 a 255).
- `[Byte 4-7]`: Payload Length (`int32`).

Formato final do frame de rede: `[Header (8 bytes)] + [Payload]`.

## 🧪 Testando na Prática (Exemplo com Python)

Como o Nexus não usa HTTP nem WebSockets, você precisa de um cliente TCP raw. Veja como o **Zero-Trust** e a publicação funcionam em 15 linhas de Python:

```python
import socket
import struct
import time

# 1. Conectar ao Broker
s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
s.connect(('127.0.0.1', 9090))

# 2. Handshake Zero-Trust (Obrigatório)
# Enviamos o SERVER_SECRET (0xCAFEBABE12345678) em Big-Endian ('>Q')
handshake = struct.pack('>Q', 0xCAFEBABE12345678)
s.sendall(handshake)
print("Handshake enviado. Conexão liberada!")

# 3. Publicar uma Mensagem (PUB)
payload = b"HELLO NEXUS"
# Header: Magic('NM'), Flags(0x01=PUB), TopicHash(0x42), PayloadLength
header = struct.pack('>2s b b i', b'NM', 0x01, 0x42, len(payload))

s.sendall(header + payload)
print(f"Mensagem enviada com sucesso: {payload}")

time.sleep(1)
s.close()
```

### O que acontece se um Hacker tentar conectar?
Se alguém abrir um terminal e rodar `telnet 127.0.0.1 9090` e tentar mandar comandos genéricos ou lixo:
1. O socket abre, mas fica preso no limbo (`AWAITING_HANDSHAKE`).
2. O hacker tenta mandar os bytes "HACK".
3. O decodificador percebe que os bytes não formam a chave secreta `0xCAFEBABE12345678L`.
4. O servidor joga silenciosamente a `ProtocolException` e **fecha a conexão TCP instantaneamente** na cara do hacker, sem gastar 1 byte de processamento do LMAX Disruptor.

## 🤝 Contribuindo

Pull requests são sempre bem-vindos. O critério principal de aceite no core do broker é simples: **não adicione alocações ou locks pesados no hot-path de rede**.

1. Faça um fork e crie uma branch (`feature/seu-recurso` ou `perf/sua-otimizacao`).
2. Garanta que suas mudanças não adicionam contenção de threads ou GCs (Garbage Collections).
3. Abra o PR com um resumo técnico das mudanças e seu impacto na latência.

**Principais áreas para colaboração:**
- Afinidade de CPU via JNI (Thread Pinning em SOs baseados em Unix).
- Construção de SDKs nativos em Rust, C++ e Go (implementando o Zero-Trust Handshake).
- Refinamentos na mitigação nativa do Linux Epoll Bug.
