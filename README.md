
SHIWA
===========

![build](https://img.shields.io/github/actions/workflow/status/........)
![version](https://img.shields.io/github/v/release/..........)

## Запуск класстеров L0 и L1 в Kubernetes

### Подготовка

1. [sbt](https://www.scala-sbt.org/)
2. [Docker Desktop](https://www.docker.com/get-started/) с включенным [Kubernetes](https://docs.docker.com/desktop/kubernetes/)
3. [Skaffold CLI](https://skaffold.dev/docs/install/#standalone-binary)

### Запуск кластеров

```
skaffold dev --trigger=manual
```

Это запустит кластеры L0 и L1 на Kubernetes с использованием текущего контекста kube.

Начальные валидаторы для L0 и L1 имеют свои общедоступные локальные порты 9000 и 9010 соответственно.

```
curl localhost:9000/cluster/info
curl localhost:9010/cluster/info
```

Это вернет список валидаторов на кластерах L0 и L1. По умолчанию как для L0, так и для L1 кластеров стартуют с 3 валидаторами 
(1 начальный и 2 регулярных).

### Масштабирование кластера

```
kubectl scale deployment/l0-validator-deployment --replicas=9
```

Это масштабирует кластер L0 до 10 валидаторов в общей сложности: 1 начальный и 9 регулярных.