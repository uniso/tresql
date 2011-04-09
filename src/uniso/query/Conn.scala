package uniso.query

import java.sql.{ Connection => C }
import java.sql.{ DriverManager => DM }
import javax.sql.{ DataSource => D }

trait Conn extends (() => C) {
    private[this] lazy val c = initConn
    private[this] lazy val d = initDataSource

    def apply() = if (d != null) d.getConnection else if (c != null) 
        DM.getConnection(c._1, c._2, c._3) else null

    def initConn = {
        init match {
            case (dbUrl: String, usr: String, pwd: String) => (dbUrl, usr, pwd) 
            case (driver: String, dbUrl: String, usr: String, pwd: String) => {
                Class.forName(driver)
                (dbUrl, usr, pwd)
            }
            case _ => null
        }
    }
    def initDataSource = {
        init match {
            case s: String => {
                val ctx = new javax.naming.InitialContext()
                ctx.lookup(s) match {
                    case ds: javax.sql.DataSource => ds
                    case x => error("not data source in jndi context: " + x)
                }
            }
            case _ => null
        }
    }
    def init: Any = (System.getProperty("uniso.query.db"), System.getProperty("uniso.query.user"),
            System.getProperty("uniso.query.password")) match {
        case params@(d:String, u:String, p:String) => params
        case _ => System.getProperty("uniso.query.data.source", "java:/comp/env/jdbc/uniso/query") 
    }

}

object Conn extends (() => Conn) {

    def apply() = new Conn {init}
    
    def apply(driver: String, dbUrl: String, user: String, password: String) = {
        new Conn {override def init = (driver, dbUrl, user, password)}        
    }
    def apply(dbUrl: String, user: String, password: String) = {
        new Conn {override def init = (dbUrl, user, password)}
    }
    
    def apply(dataSource: String) = {
        new Conn { override def init = dataSource }        
    }
}

