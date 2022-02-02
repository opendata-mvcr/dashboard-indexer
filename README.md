# Content

- [Installation](#installation)
    - [Without authentication](#without-authentication)
    - [With authentication](#with-authentication)
- [Update](#update)
- [Transfer data](#transfer-data)
    - [Transfer dashboards](#transfer-dashboards)
    - [Transfer indexer configs](#transfer-indexer-configs)
    - [Transfer indexes](#transfer-indexes)

# Installation

## Without authentication

### 1. Create `.env` file

Create `.env` file in root directory of the project (same directory as "*docker-compose.yml*"). Insert folowing (**
change variables in double asterisks `**var-name**`**):

	# Name of compose cluster of containers  
	COMPOSE_PROJECT_NAME=**cluster-name**
	
	# Ports  
	ELASTICSEARCH_PORT=**9200**
	KIBANA_PORT=**5601**    
	INDEXER_PORT=**8080**  
	
	# Additional settings   
	JAVA_MAX_MEMORY=8192M  
	JAVA_INIT_MEMORY=2048M
	INDEXER_MAX_CONCURRENT_HARVESTS=3  
	ELASTIC_STACK_VERSION=7.16.0

*Mentioned ports are widely used defaults.*

### 2. Create docker containers

Optional ( You can change settings in *docker-compose.yml* file. )

Then create images and start containers with command:

	 docker-compose up -d --build

## With authentication

### 1. Create `.env` file

Create `.env` file in root directory of the project (same directory as "*docker-compose.yml*"). Insert folowing (**only
change variables in double asterisks `**var-name**`**):

	# Name of compose cluster of containers  
	COMPOSE_PROJECT_NAME=**cluster-name**
	KIBANA_SYSTEM_PASS=***kibana-system-pass*** 
	# Kibana user credentials for indexer
	INDEXER_USERNAME=***indexer-user***  
	INDEXER_PASSWORD=***indexer-pass***  
	# Kibana user credentials for public account (auto-sign-in user)  
	PUBLIC_USERNAME=***public-user***
	PUBLIC_PASSWORD=***public-pass***
	  
	# Ports
	ELASTICSEARCH_PORT=**9200**  
	INDEXER_PORT=**8080**  
	# Kibana port (for users with account)  
	KIBANA_PORT=**5601**  
	# Kibana port with auto-sign-in (for public display)  
	PUBLIC_KIBANA_PORT=**public-port**  
  
  
	# Additional settings     
	JAVA_MAX_MEMORY=8192M  
	JAVA_INIT_MEMORY=2048M
	INDEXER_MAX_CONCURRENT_HARVESTS=3  
    KIBANA_BASE_PATH=/  
	ELASTIC_STACK_VERSION=7.16.0  
	NGINX_VERSION=1.21.4

*Mentioned ports are widely used defaults.*

### 2. Create docker containers

Optional ( You can change settings in *docker-compose-auth.yml* file. )

Create Nginx config:

	docker-compose -f create-nginx-conf.yml run --rm create_nginx_conf

Then create images and start containers with command:

	docker-compose -f docker-compose-auth.yml up -d --build

### 3. Initialize passwords in ES

Create initial passwords for Elasticsearch:

	docker exec es01 /bin/bash -c "bin/elasticsearch-setup-passwords auto --batch --url http://localhost:9200"

Save generated passwords (mainly `elastic` and `kibana_system`).

### 4. Edit .env file

Edit `.env` by changing variables in **triple asterisks**. `KIBANA_SYSTEM_PASS` to saved password `kibana_system` and
come up with user credentials for "*Indexer*" and "*Public-user*". **(Special characters are not allowed in "*
Public-user*" credentials.)**

### 5. Recreate docker containers

Recreate kibana and indexer containers.

	docker-compose -f docker-compose-auth.yml up -d --build

### 6. Setup Kibana

#### First login

Login to Kibana (default http://localhost:5601).

- Username: elastic
- Password: [saved_elastic_pass]

Create you own *user* with `superuser` role in `side menu > Stack Management > Users (under Security)` and
click `Create user`. Then relogin with your new superuser.

#### Create user for indexer

Create new role (in `side menu > Stack Management > Roles (under Security)` and click `Create role`):

1. Set `Role name` to "*indexer*"
2. ElasticSearch
    - Cluster privliges - `manage`
    - Index privliges
        - Indeces - `*`
        - Priviliges - `create`, `create_index`, ` manage`, `read`
3. Kibana
    - Click "*Add Kibana privileges*"
        - Spaces - `* All Spaces`
        - Privileges for all features - set to `Customize`
        - Customize feature privileges
            - click `Bulk actions > None`
            - set `Analytics > Dashboard` to `Read`
            - now all *feature privileges* should be `None` except for the `Analytics > Dashboard`
        - Click "*Create global privileges*"
4. Click "*Create role*"

Create user with credentials from `.env` for indexer (`INDEXER_USERNAME` and `INDEXER_PASSWORD`) and assign
role `indexer`.

#### Create public user

Create new role (in `side menu > Stack Management > Roles (under Security)` and click `Create role`):

1. Set `Role name` to "*public*"
2. ElasticSearch
    - Index privliges
        - Indeces - `*`
        - Priviliges - `read`, `view_index_metadata`
3. Kibana
    - Click "*Add Kibana privileges*"
        - Spaces - select what you want
        - Privileges for all features - set to `Customize`
        - Customize feature privileges
            - click `Bulk actions > None`
            - set `Analytics > Dashboard`, `Analytics > Discover`, `Analytics > Canvas`, `Analytics > Maps`
              and `Management > Saved Objects Management` to `Read`
        - Click "*Create global privileges*"
4. Click "*Create role*"

Create user with credentials from `.env` for indexer (`PUBLIC_USERNAME` and `PUBLIC_PASSWORD`) and assign role `public`.

### 7. (Optional) Custom deploy of Kibana for Public

If you want to open your Kibana with auto-sign-in for public with URL path (no subdomain needed).

First open `.env` file and change variable `KIBANA_BASE_PATH` (under `# Additional settings`) to your desired base
path (e.g. `/kibana`). This variable **must** start with `/`, but **can't** end with a `/`. Then open
file `docker-compose-auth.yml` and add these three lines (that sets additional environment variables for Kibana
container)

      SERVER_PUBLICBASEURL: **your-public-url**
      SERVER_BASEPATH: $KIBANA_BASE_PATH
      SERVER_REWRITEBASEPATH: "true"

to the `services > kibana > environment` section:

    version: "3.9"
    services:
        es01:
            ...
        kibana:
            ...
            environment:
                ...
                **here** <<<<-------

Field `**your-public-url**` change to your public url formatted according
to [Elastic documentation](https://www.elastic.co/guide/en/kibana/7.16/settings.html#server-publicBaseUrl). Now your
Kibana link will look something like http://localhost:5601/kibana/ and your auto-sing-in Kibana (just on a different
port) as well.

If you are using Nginx as your main proxy. Here is an example of location config:

    location /kibana/ {
        proxy_pass http://localhost:1234/kibana/;
        proxy_pass_request_headers      on;
        proxy_set_header   X-Real-IP        $remote_addr;
        proxy_set_header   X-Forwarded-For  $proxy_add_x_forwarded_for;
    }

Where the port is `**public-port**` from `.env` file.

(*If you get stuck in login loop, you need to delete your browser cookies for this page.*)

To revert this action, change variable `KIBANA_BASE_PATH` in `.env` back to `/` and remove the three added lines
from `docker-compose-auth.yml`. Then execute [update](#update) sequence.

# Update

To update your deployment just download new version from git repository (check if template of `.env` for your deployment
changed in [Installation](#installation)). Then execute:

    docker build . --no-cache

Then execute command(s) from [Installation](#installation) step 2. of your deployment. (***Warning: this will create a
new docker image for indexer and old one isn't going to be deleted (just renamed to `<none>`).
Solution: [docker image prune](https://docs.docker.com/engine/reference/commandline/image_prune/#filtering).***)

If you only changed `.env`, `docker-compose.yml` or `docker-compose-auth.yml` just execute command(s)
from [Installation](#installation) step 2. of your deployment.

# Transfer data

If you want to move your data and configs to another deployment.

## Transfer dashboards

### Export

In Kibana go to ``side menu > Stack Management > Save Objects (under Kibana)`` and select dashboard you want.
Click ``Export`` (select Include related objects) and ``Export``.

### Import

In Kibana go to ``side menu > Stack Management > Save Objects (under Kibana)``. Click on ``Import``. Select file by
clicking on *Import rectangle* or mouse over the file (*export.ndjson*) on *Import rectangle*. Select Import option and
click ``Import``.

## Transfer indexer configs

### Export

Click on icon of `Export configs` on main page. This will download a file `indexer-configs.conf` containing all
configs. (Icon is located above the main table (with indexes), on the right side next to the `Status` circle icon)

### Import

Click on icon of `Import configs` on main page. (Icon is located above the main table (with indexes), on the right side
next to the `Status` circle icon)
This will show a file dialog, where you need to locate and select the file with indexer configs (default export name
is `indexer-configs.conf`).

## Transfer indexes

Use if you need to transfer indexed data in your elastic.

### Prerequirements:

- Install elasticdump tool (https://github.com/elasticsearch-dump/elasticsearch-dump).

      npm install elasticdump -g

- Created directory for export (e.g. ``backup``)

### Export

Execute command:

    multielasticdump --match=regex --includeType=data,mapping --output=backup --input=http://username:password@localhost:9200

### Inport

Execute command:

    multielasticdump --direction=load --includeType=data,mapping --input=backup --output=http://username:password@localhost:9200 

-----

Nástroj pro indexování RDF dat pro Elasticsearch. Tento repozitář je udržován v rámci projektu OPZ
č. [CZ.03.4.74/0.0/0.0/15_025/0013983](https://esf2014.esfcr.cz/PublicPortal/Views/Projekty/Public/ProjektDetailPublicPage.aspx?action=get&datovySkladId=F5E162B2-15EC-4BBE-9ABD-066388F3D412)
.  
![Evropská unie - Evropský sociální fond - Operační program Zaměstnanost](https://data.gov.cz/images/ozp_logo_cz.jpg)
