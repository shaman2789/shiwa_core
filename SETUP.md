
# SETUP

Это руководство поможет вам настроить необходимые инструменты для разработки.

1. Установите _Java 11_, [ссылка](https://openjdk.java.net/projects/jdk/11/).
2. Установите _SBT_, [ссылка](https://www.scala-sbt.org/).

## Fedora Linux

1. Установите [_Docker Engine_](https://docs.docker.com/engine/install/fedora/).
2. Выполните пост-установочные шаги, [ссылка](https://docs.docker.com/engine/install/linux-postinstall/).
3. Установите [_kubectl_](https://docs.docker.com/desktop/kubernetes/).

В качестве альтернативы просто установите [_Docker Desktop_](https://docs.docker.com/desktop/linux/install/fedora/) и включите [_Kubernetes_](https://docs.docker.com/desktop/kubernetes/).

## MacOS

1. Установите [_Docker Desktop_](https://docs.docker.com/desktop/mac/install/).
2. Включите _Kubernetes_ через _Docker Desktop_, [ссылка](https://docs.docker.com/desktop/kubernetes/).

# Установка Java 11

Установка _Java 11_ может отличаться в зависимости от вашей операционной системы и выбранной методологии. Рекомендуемая версия для установки - _OpenJDK 11_.

## Fedora Linux

Установка через _DNF_ рекомендуется для Fedora Linux. Инструкции можно найти [здесь](https://docs.fedoraproject.org/en-US/quick-docs/installing-java/).

## Mac OS

Установка через [_Homebrew_](https://brew.sh/) является рекомендуемым методом для Mac, хотя он может использоваться и для других операционных систем. Чтобы установить _OpenJDK 11_, перейдите [сюда](https://formulae.brew.sh/formula/openjdk@11#default).

## SDKMan

[_SDKMan_](https://sdkman.io/) - это кросс-платформенный инструмент для управления версиями SDK. Инструкции по его использованию находятся [здесь](https://sdkman.io/usage). Как и в других методах, рекомендуется установить _OpenJDK 11_. Для случаев, когда это невозможно, подойдут другие качественные реализации _JDK 11_ (например, _Coretto Java 11_, _Liberica Java 11_ или _Zulu Java 11_).

# Установка SBT

Установите последнюю версию _SBT_, чтобы скомпилировать кодовую базу и запустить модульные тесты.

После установки _SBT_ его необходимо настроить. Инструкции для этого приведены в файле _CONTRIBUTING.md_.

## Fedora Linux

На Linux можно следовать инструкциям, предоставленным _SBT_, [ссылка](https://www.scala-sbt.org/1.x/docs/Installing-sbt-on-Linux.html).

## Mac OS

На Mac OS _SBT_ предоставляет инструкции [здесь](https://www.scala-sbt.org/1.x/docs/Installing-sbt-on-Mac.html). Его также можно установить через [_Homebrew_](https://formulae.brew.sh/formula/sbt#default). Рекомендуется также установить _SBTEnv_, [ссылка](https://formulae.brew.sh/formula/sbtenv#default). Он поможет настроить среду _SBT_.

# Запуск L0 & L1 на кластере EKS

## Предварительные условия

1. [sbt](https://www.scala-sbt.org/)
2. [Docker Desktop](https://www.docker.com/get-started/) с включенным [Kubernetes](https://docs.docker.com/desktop/kubernetes/)
3. [Skaffold CLI](https://skaffold.dev/docs/install/#standalone-binary)
4. [AWS CLI версии 2](https://docs.aws.amazon.com/cli/latest/userguide/getting-started-install.html)

## Настройка кластера Kubernetes

### Обновление файла kubeconfig

```
aws eks --region us-west-1 update-kubeconfig --name eks-dev
kubectl config rename-context $(kubectl config current-context) eks-dev
```

### Создание вашего пространства имен

```
IAM_USER=$(aws sts get-caller-identity --query Arn --output text | sed 's/.*\///g')

kubectl create namespace $IAM_USER
kubectl config set-context --current --namespace=$IAM_USER
```

### Проверка настройки Kubernetes

```
kubectl get pods
```

Должно вернуть:

```
No resources found in <your-namespace-name> namespace.
```

## Настройка репозитория образов Docker

### Установка учетной записи Docker credential helper

```
brew install docker-credential-helper-ecr
```

### Обновление конфигурации Docker

Добавьте это в ваш `~/.docker/config.json`

```json
{
  "credHelpers": {
    "public.ecr.aws": "ecr-login",
    "150340915792.dkr.ecr.us-west-1.amazonaws.com": "ecr-login"
  }
}
```

### Обновление конфигурации Skaffold

```
skaffold config set default-repo 150340915792.dkr.ecr.us-west-1.amazonaws.com
```

### Проверка настройки Docker

```
docker image ls 150340915792.dkr.ecr.us-west-1.amazonaws.com/l0-validator
```

Должен перечислять существующие образы l0-validator.

## Сборка образов и запуск кластера

```
skaffold dev --trigger manual --tail=false
```

Вы должны увидеть успешную загрузку образов Docker в контейнерный реестр, а затем успешное развертывание ресурсов Kubernetes на кластере EKS. Откройте Grafana, чтобы отслеживать производительность кластеров L0 и L1 [http://localhost:3000](http://localhost:3000).

Чтобы получить доступ к API отдельных pod-ов, вы можете использовать HTTP-прокси. Сначала получите IP-адрес
pod-а в кластере.

```
kubectl get pods -o wide
```

Затем установите переменную окружения `http_proxy` и используйте curl для запроса pod-а.

```
export http_proxy=8080

curl <pod-ip-address>:9000/cluster/info
```

### Активация профилей

Активируйте профили с помощью опции `-p`. Профили также могут быть деактивированы вручную, добавив префикс с минусом перед именем профиля.

```
skaffold dev -p foo,-bar
```

### Профили

- chaos - внедрение экспериментов с хаосом в кластер (например, сбой некоторого количества pod-ов)