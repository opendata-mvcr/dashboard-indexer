# Instalation

## Without authentication

### 1. Create `.env` file

Create `.env` file in root directory of the project (same directory as "*docker-compose.yml*"). Insert folowing (**change variables in double asterisks `**var-name**`**):

	# Name of compose cluster of containers  
	COMPOSE_PROJECT_NAME=**cluster-name**
	
	# Ports  
	ELASTICSEARCH_PORT=**9200**
	KIBANA_PORT=**5601**    
	INDEXER_PORT=**8080**  
	
	# Aditional settings   
	JAVA_MAX_MEMORY=8192M  
	JAVA_INIT_MEMORY=2048M
	INDEXER_MAX_THREADS=4  
	ELASTIC_STACK_VERSION=7.16.0

*Mentioned ports are widely used defaults.*

### 2. Create docker containers

Optional ( You can change settings in *docker-compose.yml* file. )

Then create images and start containers with command:

	 docker-compose up -d --build

## With authentication

### 1. Create `.env` file

Create `.env` file in root directory of the project (same directory as "*docker-compose.yml*"). Insert folowing (**only change variables in double asterisks `**var-name**`**):


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
  
  
	# Aditional settings     
	JAVA_MAX_MEMORY=8192M  
	JAVA_INIT_MEMORY=2048M
	INDEXER_MAX_THREADS=4  
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

Edit `.env` by changing variables in **triple asterisks**. `KIBANA_SYSTEM_PASS` to saved password `kibana_system` and come up with user credentials for "*Indexer*" and "*Public-user*". **(Special characters are not allowed in "*Public-user*" credentials.)**

### 5. Recreate docker containers

Recreate kibana and indexer containers.

	docker-compose -f docker-compose-auth.yml up -d --build

### 6. Setup Kibana

#### First login

Login to Kibana (default http://localhost:5601).

- Username: elastic
- Password: [saved_elastic_pass]

Create you own *user* with `superuser` role in `side menu > Stack Management > Users (under Security)` and click `Create user`. Then relogin with your new superuser.

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

Create user with credentials from `.env` for indexer (`INDEXER_USERNAME` and `INDEXER_PASSWORD`) and assign role `indexer`.

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
            - set `Analytics > Dashboard`, `Analytics > Discover`, `Analytics > Canvas`, `Analytics > Maps` and `Management > Saved Objects Management` to `Read`
        - Click "*Create global privileges*"
4. Click "*Create role*"

Create user with credentials from `.env` for indexer (`PUBLIC_USERNAME` and `PUBLIC_PASSWORD`) and assign role `public`.

-----

Nástroj pro indexování RDF dat pro Elasticsearch. Tento repozitář je udržován v rámci projektu OPZ č. [CZ.03.4.74/0.0/0.0/15_025/0013983](https://esf2014.esfcr.cz/PublicPortal/Views/Projekty/Public/ProjektDetailPublicPage.aspx?action=get&datovySkladId=F5E162B2-15EC-4BBE-9ABD-066388F3D412).  
![Evropská unie - Evropský sociální fond - Operační program Zaměstnanost](https://data.gov.cz/images/ozp_logo_cz.jpg)
