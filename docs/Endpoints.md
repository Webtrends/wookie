# Wookiee - Default HTTP Endpoints

## Overview

The endpoints below are simple HTTP requests to our applications that can return some basic information. If an
application is an externally facing web service then endpoints will only be allowed from internal points. CIDR rules
are applied to some of the calls and are labeled in the table below.

## Available Endpoints

| Description           | Verb  | URL                 | Description   | Example   | CIDR Rules    |
|:----------------------| :---- |:--------------------| ------------- | --------- | ----------    |
| Ping                  | GET   | /ping               |               |           | No            |
| Health Check (Full)   | GET   | /healthcheck        |               |           | Yes           |
| Health Check (Nagios) | GET   | /healthcheck/nagios |               |           | Yes           |
| Health Check (LB)     | GET   | /healthcheck/lb     |               |           | Yes           |
| Metrics               | GET   | /metrics            |               |           | Yes           |
| Favicon               | GET   | /favicon.ico        |               |           | Yes           |


## CIDR (Classless Inter-Domain Routing) Rules

These rules are used to allow/deny certain IPs from gaining access to HTTP endpoints. We default to using a range
of values to allow access:

127.0.0.1/30 -> 127.0.0.0 - 127.0.0.3
10.0.0.0/8 -> 10.0.0.0 - 10.255.255.255

This results in the following output in the application.conf file:

     # This section is used to support CIDR notation to allow or block calls to
     # certain services
     cidr {
        # This is a list of IP ranges to allow through. Can be empty.
        allow=["127.0.0.1/30", "10.0.0.0/8"]
        # This is a list of IP ranges to specifically deny access. Can be empty.
        deny=[]
     }
	 
Currently the CIDR rules do not support IPv6, so if you have setup the CIDR rules and an IPv6 address comes into Wookiee it will simply fail and send back a NotFound.