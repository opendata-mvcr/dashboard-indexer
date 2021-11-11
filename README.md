# Instalation

## HTTP

### 1.  Building server application

Copy production build of frontend application ([link](https://github.com/opendata-mvcr/dashboard-indexer-frontend)) to *frontend/build*   
Build server aplication:

	 mvn clean install  

### 2. Create .env file

Create .env file in root directory of the project (same directory as "*docker-compose.yml*"). Insert folowing:

	COMPOSE_PROJECT_NAME=**server**
	VERSION=7.10.0

Change variable in **double asterisks** `**var-name**`

### 3. Create docker containers

Change settings in *docker-compose.yml* file.  Then create images and start containers with command:

	 docker-compose up -d

## HTTPS

### 1.  Building server application

Copy production build of frontend application ([link](https://gitlab.fel.cvut.cz/svagrmic/bp-application)) to *frontend/build*

Build server aplication:

	mvn clean install

### 2. Create .env file

Create .env file in root directory of the project (same directory as "*docker-compose.yml*"). Insert folowing:


	COMPOSE_PROJECT_NAME=**server**
	CERTS_DIR=/usr/share/elasticsearch/config/certificates
	KIBANA_SYSTEM_PASS=***kibana_system-pass***
	INDEXER_USERNAME=***my-indexer-user***
	INDEXER_PASSWORD=***my-indexer-pass***
	VERSION=7.10.0

**Only change variable in double asterisks `**var-name**` !!!**

### 3. Create docker containers

You can change settings in *docker-compose-https.yml* file. Then create images and start containers with command:

	docker-compose -f create-certs.yml run --rm create_certs
	docker-compose -f docker-compose-https.yml up -d

### 4. Initialize passwords in ES

Create initial passwords for Elasticsearch:

	docker exec es01 /bin/bash -c "bin/elasticsearch-setup-passwords auto --batch --url https://localhost:9200"

Save generated password `elastic` and `kibana_system`.

### 5. Edit .env file

Edit `.env` by changing variables in **triple asterisks**. Change `KIBANA_SYSTEM_PASS` to saved password `kibana_system`. Others as you wish.

### 6. Recreate docker containers

Recreate kibana and indexer containers.

	docker-compose -f docker-compose-https.yml up -d

### 7. Setup Kibana

Login to Kibana (https://localhost:5601).

- Username: elastic
- Password: [saved_elastic_pass]

Create you own *user* with `superuser` role in `side menu > Stack Management > Users (under Security)`. Relogin with your new superuser.

Create new role (in `side menu > Stack Management > Roles (under Security)`):

1. Set `Role name` to "*indexer*"
2. ElasticSearch
    - Cluster privliges - `manage`
    - Index privliges
        - Indeces - `*`
        - Priviliges - `create`, `create_index`, ` manage`, `read`
3. Kibana
    - Add Kibana privileges
        - Spaces - `* All Spaces`
        - Privileges for all features - set to `Customize`
        - Customize feature privileges
            - click `Bulk actions > None`
            - set `Kibana > Dashboard` to `Read`
            - now all *feature privileges* should be `None` except for the `Kibana > Dashboard`
        - Click "*Create global privileges*"
4. Click "*Create role*"

Create user with credentials from `.env` for indexer (`INDEXER_USERNAME` and `INDEXER_PASSWORD`) and assign role `indexer`.

-----

Nástroj pro indexování RDF dat pro Elasticsearch. Tento repozitář je udržován v rámci projektu OPZ č. [CZ.03.4.74/0.0/0.0/15_025/0013983](https://esf2014.esfcr.cz/PublicPortal/Views/Projekty/Public/ProjektDetailPublicPage.aspx?action=get&datovySkladId=F5E162B2-15EC-4BBE-9ABD-066388F3D412).  
![Evropská unie - Evropský sociální fond - Operační program Zaměstnanost](https://data.gov.cz/images/ozp_logo_cz.jpg)